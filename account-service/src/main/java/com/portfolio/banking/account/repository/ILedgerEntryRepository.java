package com.portfolio.banking.account.repository;

import com.portfolio.banking.account.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ILedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /**
     * The idempotency lookup: has this operation already been posted to this
     * account? Backed by the {@code (account_id, operation_key)} unique index,
     * so it stays a single index hit no matter how long an account's history
     * grows.
     */
    Optional<LedgerEntry> findByAccountIdAndOperationKey(UUID accountId, String operationKey);

    /** An account statement: newest posting first. */
    List<LedgerEntry> findAllByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
