package com.platinumcoin.auth.api;

import com.platinumcoin.auth.api.dto.MeResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identidade extraída do access token já validado (RS256 via JWKS pelo
 * resource server). Nada aqui consulta o Keycloak — só confia no token.
 */
@RestController
public class MeController {

    @GetMapping("/v1/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new MeResponse(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("accountId"));
    }
}
