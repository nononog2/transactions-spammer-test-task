package com.example.platformcore.api

import com.example.platformcore.exception.NotFoundException
import com.example.platformcore.exception.OverloadedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

data class ErrorResponse(
    val timestamp: Instant,
    val status: Int,
    val error: String,
    val message: String,
)

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(Instant.now(), 404, "NOT_FOUND", ex.message ?: "Resource not found"),
        )

    @ExceptionHandler(OverloadedException::class)
    fun handleOverloaded(ex: OverloadedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
            ErrorResponse(Instant.now(), 429, "OVERLOADED", ex.message ?: "Service is overloaded"),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = ex.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(
            ErrorResponse(Instant.now(), 400, "VALIDATION_ERROR", details),
        )
    }
}
