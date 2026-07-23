package com.platinumcoin.payments.api;

import com.platinumcoin.payments.domain.error.AccountMismatchException;
import com.platinumcoin.payments.domain.error.IdempotencyConflictException;
import com.platinumcoin.payments.domain.error.MissingAccountClaimException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Erros RFC 7807 (application/problem+json) com `code` e `correlationId`.
 * Nunca stack trace nem detalhe interno na resposta (fail-closed).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Requisição inválida", "Um ou mais campos são inválidos");
        problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList());
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Requisição inválida", "Corpo da requisição malformado");
    }

    @ExceptionHandler(AccountMismatchException.class)
    public ProblemDetail handleAccountMismatch(AccountMismatchException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_MISMATCH",
                "Conta divergente",
                "O accountId do corpo diverge do accountId do token; a conta debitada é sempre a do token");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException ex) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "Conflito de idempotência",
                "A Idempotency-Key já foi usada com um payload diferente; use uma key nova para uma nova operação");
    }

    @ExceptionHandler(MissingAccountClaimException.class)
    public ProblemDetail handleMissingAccountClaim(MissingAccountClaimException ex) {
        return problem(HttpStatus.FORBIDDEN, "MISSING_ACCOUNT_CLAIM",
                "Acesso negado", "O token não identifica uma conta");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Erro não tratado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Erro interno", "Erro inesperado ao processar a requisição");
    }

    private Map<String, String> fieldError(FieldError error) {
        // Só nome do campo e mensagem — nunca o valor rejeitado.
        return Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage()));
    }

    private ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setProperty("code", code);
        problem.setProperty("correlationId", MDC.get("correlationId"));
        return problem;
    }
}
