package com.portfolio.banking.notification.repository;

import com.portfolio.banking.notification.model.ProcessedEvent;
import com.portfolio.banking.notification.model.ProcessedEventId;
import org.springframework.data.jpa.repository.JpaRepository;


public interface IProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {
    // No custom finder: dedup is enforced by the primary key itself via
    // saveAndFlush + catching the constraint violation, not a check-then-act.
}
