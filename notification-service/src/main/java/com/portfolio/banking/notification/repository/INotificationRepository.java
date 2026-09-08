package com.portfolio.banking.notification.repository;

import com.portfolio.banking.notification.model.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface INotificationRepository extends JpaRepository<Notification, UUID> {

    /** First page of one account's notifications, newest first. */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.recipientAccountId = :recipientAccountId
            ORDER BY n.createdAt DESC, n.id DESC
            """)
    List<Notification> findFirstPageByRecipientAccountId(@Param("recipientAccountId") UUID recipientAccountId,
                                                          Pageable pageable);

    /**
     * The page after the row identified by {@code (afterCreatedAt, afterId)}.
     * Ordered by both columns so the resume position is unique - see
     * {@code KeysetPage}.
     */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.recipientAccountId = :recipientAccountId
              AND (n.createdAt < :afterCreatedAt
                   OR (n.createdAt = :afterCreatedAt AND n.id < :afterId))
            ORDER BY n.createdAt DESC, n.id DESC
            """)
    List<Notification> findPageByRecipientAccountIdAfter(@Param("recipientAccountId") UUID recipientAccountId,
                                                          @Param("afterCreatedAt") Instant afterCreatedAt,
                                                          @Param("afterId") UUID afterId,
                                                          Pageable pageable);
}
