package com.zondamarket.identity.repository;

import com.zondamarket.identity.domain.RefreshSession;
import com.zondamarket.identity.domain.UserAccount;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from RefreshSession s
            where s.id = :id and s.account.id = :accountId and not s.deleted and not s.account.deleted
            """)
    Optional<RefreshSession> lockFamily(UUID id, UUID accountId);

    @Query("""
            select s.account from RefreshSession s
            where s.id = :id and s.account.id = :accountId and s.sessionVersion = :version
                and s.expiresAt > :now and s.revokedAt is null and not s.deleted and not s.account.deleted
            """)
    Optional<UserAccount> findActiveAccount(
            UUID id, UUID accountId, long version, Instant now);
}
