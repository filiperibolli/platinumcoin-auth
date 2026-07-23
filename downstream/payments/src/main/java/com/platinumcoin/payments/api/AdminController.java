package com.platinumcoin.payments.api;

import com.platinumcoin.payments.api.dto.PixResponse;
import com.platinumcoin.payments.domain.service.PixService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Visão de suporte (Fatia 4 — RBAC): consulta os comprovantes, não movimenta nada.
 * O acesso exige a role `support` (regra central no SecurityConfig, `/v1/admin/**`);
 * `customer` recebe 403 aqui, e `support` recebe 403 no /v1/pix — a separação é
 * estrutural, por role, não por if no handler.
 */
@RestController
public class AdminController {

    private final PixService pixService;

    public AdminController(PixService pixService) {
        this.pixService = pixService;
    }

    @GetMapping("/v1/admin/receipts")
    public List<PixResponse> receipts() {
        return pixService.receipts().stream().map(PixResponse::from).toList();
    }
}
