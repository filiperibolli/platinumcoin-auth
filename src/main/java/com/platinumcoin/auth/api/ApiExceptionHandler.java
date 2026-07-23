package com.platinumcoin.auth.api;

import com.platinumcoin.auth.domain.error.EmailAlreadyRegisteredException;
import com.platinumcoin.auth.domain.error.IdentityProviderUnavailableException;
import com.platinumcoin.auth.domain.error.InvalidCpfException;
import com.platinumcoin.auth.domain.error.InvalidCredentialsException;
import com.platinumcoin.auth.domain.error.InvalidRefreshTokenException;
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

    @ExceptionHandler(InvalidCpfException.class)
    public ProblemDetail handleInvalidCpf(InvalidCpfException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_CPF", "CPF inválido", ex.getMessage());
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ProblemDetail handleConflict(EmailAlreadyRegisteredException ex) {
        return problem(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                "Conflito", "E-mail já cadastrado");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        // Mensagem genérica de propósito: não revela se usuário existe ou se a senha errou.
        return problem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Não autorizado", "Credenciais inválidas");
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        // Genérico de propósito: expirado, rotacionado (reuso) ou revogado → mesma resposta.
        return problem(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "Não autorizado", "Refresh token inválido");
    }

    @ExceptionHandler(IdentityProviderUnavailableException.class)
    public ProblemDetail handleUpstream(IdentityProviderUnavailableException ex) {
        log.error("IdP indisponível", ex);
        return problem(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE",
                "Serviço indisponível", "Não foi possível concluir a operação");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Erro não tratado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Erro interno", "Erro inesperado ao processar a requisição");
    }

    private Map<String, String> fieldError(FieldError error) {
        // Só nome do campo e mensagem — nunca o valor rejeitado (pode ser senha/CPF).
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
