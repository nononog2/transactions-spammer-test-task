package com.example.platformcore.api

import com.example.platformcore.service.CreateTransactionCommand
import com.example.platformcore.service.TransactionService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController(
    private val transactionService: TransactionService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun create(@Valid @RequestBody request: CreateTransactionRequest): CreateTransactionResponse {
        val tx = transactionService.create(
            CreateTransactionCommand(
                requestId = request.requestId,
                accountFrom = request.accountFrom,
                accountTo = request.accountTo,
                amount = request.amount,
                currency = request.currency,
            ),
        )
        return tx.toCreateResponse()
    }

    @GetMapping("/{transactionId}")
    fun getById(@PathVariable transactionId: UUID): TransactionResponse =
        transactionService.getById(transactionId).toResponse()
}
