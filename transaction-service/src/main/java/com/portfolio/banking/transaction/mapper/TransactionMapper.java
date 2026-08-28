package com.portfolio.banking.transaction.mapper;

import com.portfolio.banking.transaction.dto.TransferResponse;
import com.portfolio.banking.transaction.model.Transaction;
import org.springframework.stereotype.Component;

@Component
public class TransactionMapper implements ITransactionMapper {

    @Override
    public TransferResponse toResponse(Transaction transaction) {
        return new TransferResponse(
                transaction.getId(),
                transaction.getSourceAccountId(),
                transaction.getDestinationAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus().name(),
                transaction.getFailureReason(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt()
        );
    }
}
