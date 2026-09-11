package com.portfolio.banking.notification.repository;

import com.portfolio.banking.notification.model.AccountOwner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IAccountOwnerRepository extends JpaRepository<AccountOwner, UUID> {
}
