package com.zondamarket.identity.repository;

import com.zondamarket.identity.domain.RefreshSession;
import com.zondamarket.identity.domain.UserAccount;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from RefreshSession s
            where s.id = :id and s.account.id = :accountId and not s.deleted
                and exists (select 1 from UserAccount a where a.id = :accountId and not a.deleted)
            """)
    Optional<RefreshSession> lockFamily(UUID id, UUID accountId);

    @Modifying
    @Query("""
            update RefreshSession s set s.revokedAt = :now, s.sessionVersion = s.sessionVersion + 1, s.updatedAt = :now
            where s.account.id = :accountId and s.revokedAt is null and not s.deleted
            """)
    void revokeAllForAccount(UUID accountId, Instant now);

    @Query("""
            select s.account from RefreshSession s
            where s.id = :id and s.account.id = :accountId and s.sessionVersion = :version
                and s.expiresAt > :now and s.revokedAt is null and not s.deleted and not s.account.deleted
            """)
    Optional<UserAccount> findActiveAccount(
            UUID id, UUID accountId, long version, Instant now);
}
