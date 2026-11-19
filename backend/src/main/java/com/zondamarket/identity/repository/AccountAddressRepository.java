package com.zondamarket.identity.repository;

import com.zondamarket.identity.domain.AccountAddress;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AccountAddressRepository extends JpaRepository<AccountAddress, UUID> {
    List<AccountAddress> findByAccountIdAndDeletedFalseOrderByCreatedAtAscIdAsc(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountAddress a where a.id = :id and a.accountId = :accountId and not a.deleted")
    Optional<AccountAddress> lockOwned(UUID id, UUID accountId);
}
