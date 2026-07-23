package com.platinumcoin.auth.domain.model;

import com.platinumcoin.auth.domain.error.InvalidCpfException;

/**
 * CPF normalizado (11 dígitos) e validado pelos dois dígitos verificadores.
 * Aceita entrada com ou sem máscara (390.533.447-05 ou 39053344705).
 */
public record Cpf(String digits) {

    public static Cpf parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidCpfException("CPF é obrigatório");
        }
        String digits = raw.replaceAll("[.\\-\\s]", "");
        if (!digits.matches("\\d{11}")) {
            throw new InvalidCpfException("CPF deve ter 11 dígitos");
        }
        if (digits.chars().distinct().count() == 1) {
            throw new InvalidCpfException("CPF inválido");
        }
        if (checkDigit(digits, 9) != digits.charAt(9) - '0'
                || checkDigit(digits, 10) != digits.charAt(10) - '0') {
            throw new InvalidCpfException("CPF inválido");
        }
        return new Cpf(digits);
    }

    private static int checkDigit(String digits, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (digits.charAt(i) - '0') * (length + 1 - i);
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }
}
