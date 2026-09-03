package com.portfolio.banking.notification.repository;

import com.portfolio.banking.notification.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    // No custom finder: dedup is enforced by the primary key itself via
    // saveAndFlush + catching the constraint violation, not a check-then-act.
}
