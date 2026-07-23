package com.platinumcoin.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(@NotBlank @Email String email) {
}
