package com.platinumcoin.auth.api;

import com.platinumcoin.auth.api.dto.ChangePasswordRequest;
import com.platinumcoin.auth.api.dto.ForgotPasswordRequest;
import com.platinumcoin.auth.api.dto.LoginRequest;
import com.platinumcoin.auth.api.dto.LoginResponse;
import com.platinumcoin.auth.api.dto.LogoutRequest;
import com.platinumcoin.auth.api.dto.RefreshRequest;
import com.platinumcoin.auth.api.dto.RegisterRequest;
import com.platinumcoin.auth.api.dto.RegisterResponse;
import com.platinumcoin.auth.api.dto.VerifyEmailRequest;
import com.platinumcoin.auth.domain.model.AuthTokens;
import com.platinumcoin.auth.domain.model.RegisteredUser;
import com.platinumcoin.auth.domain.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        RegisteredUser user = authService.register(
                request.email(), request.password(), request.fullName(), request.cpf());
        return new RegisterResponse(
                user.userId(), user.email(), user.fullName(), user.cpf(), user.accountId());
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return toResponse(authService.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return toResponse(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
    }

    // 202 sempre, exista o e-mail ou não: a resposta não pode servir de oráculo
    // de quais e-mails têm conta (anti-enumeração). Vale para os dois endpoints.
    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.resendVerificationEmail(request.email());
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
    }

    // Autenticado (não está no permitAll): o e-mail vem do token, nunca do corpo —
    // um token de A não troca a senha de B.
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(
                jwt.getClaimAsString("email"), request.currentPassword(), request.newPassword());
    }

    private LoginResponse toResponse(AuthTokens tokens) {
        return new LoginResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.expiresIn(),
                tokens.refreshExpiresIn(),
                tokens.tokenType());
    }
}
