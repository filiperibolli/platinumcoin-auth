package com.platinumcoin.payments.infra.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Exige a audiência deste serviço no token (ADR-006): um token emitido para outro
 * serviço, mesmo que válido e do mesmo realm, não é aceito aqui (confused deputy).
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error MISSING_AUDIENCE = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN, "Audiência requerida ausente", null);

    private final String requiredAudience;

    public AudienceValidator(String requiredAudience) {
        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (token.getAudience() != null && token.getAudience().contains(requiredAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(MISSING_AUDIENCE);
    }
}
