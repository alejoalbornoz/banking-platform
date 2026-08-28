package com.portfolio.banking.account.repository;

import com.portfolio.banking.account.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IAccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findAllByOwnerId(UUID ownerId);

    boolean existsByAccountNumber(String accountNumber);
}
