package com.platinumcoin.payments.api;

import com.platinumcoin.payments.api.dto.PixRequest;
import com.platinumcoin.payments.api.dto.PixResponse;
import com.platinumcoin.payments.domain.error.AccountMismatchException;
import com.platinumcoin.payments.domain.error.MissingAccountClaimException;
import com.platinumcoin.payments.domain.service.PixService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regra de ouro: a conta debitada é o claim `accountId` do token já validado
 * (RS256 via JWKS + aud). Um accountId no corpo só serve para detectar fraude:
 * divergente → rejeita.
 */
@RestController
public class PixController {

    private final PixService pixService;

    public PixController(PixService pixService) {
        this.pixService = pixService;
    }

    @PostMapping("/v1/pix")
    @ResponseStatus(HttpStatus.CREATED)
    public PixResponse send(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PixRequest request) {
        String tokenAccountId = jwt.getClaimAsString("accountId");
        if (tokenAccountId == null || tokenAccountId.isBlank()) {
            throw new MissingAccountClaimException();
        }
        if (request.accountId() != null && !request.accountId().equals(tokenAccountId)) {
            throw new AccountMismatchException();
        }
        return PixResponse.from(pixService.send(tokenAccountId, request.pixKey(), request.amount()));
    }
}
