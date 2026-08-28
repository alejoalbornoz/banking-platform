package com.portfolio.banking.transaction.mapper;

import com.portfolio.banking.transaction.dto.TransferResponse;
import com.portfolio.banking.transaction.model.Transaction;

public interface ITransactionMapper {

    TransferResponse toResponse(Transaction transaction);
}
