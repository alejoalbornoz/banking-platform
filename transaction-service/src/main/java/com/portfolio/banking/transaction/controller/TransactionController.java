package com.portfolio.banking.transaction.controller;

import com.portfolio.banking.transaction.dto.TransferRequest;
import com.portfolio.banking.transaction.dto.TransferResponse;
import com.portfolio.banking.transaction.service.ITransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransactionController {

    private final ITransferService transferService;

    public TransactionController(ITransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                       @Valid @RequestBody TransferRequest request) {
        TransferResponse response = transferService.transfer(idempotencyKey, request);
        return ResponseEntity.status(statusFor(response)).body(response);
    }

    @GetMapping("/{transactionId}")
    public TransferResponse getTransaction(@PathVariable UUID transactionId) {
        return transferService.getTransaction(transactionId);
    }

    /** 201 when this call is the one that completed the transfer; 200 for anything else (failed, or a replayed result). */
    private HttpStatus statusFor(TransferResponse response) {
        return "COMPLETED".equals(response.status()) ? HttpStatus.CREATED : HttpStatus.OK;
    }
}
