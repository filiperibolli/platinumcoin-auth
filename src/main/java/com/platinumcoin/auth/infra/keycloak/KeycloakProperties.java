package com.platinumcoin.auth.infra.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platinumcoin.keycloak")
public record KeycloakProperties(
        String baseUrl,
        String realm,
        String harnessClientId,
        String adminClientId,
        String adminClientSecret) {

    public String realmUrl() {
        return baseUrl + "/realms/" + realm;
    }

    public String tokenEndpoint() {
        return realmUrl() + "/protocol/openid-connect/token";
    }

    public String logoutEndpoint() {
        return realmUrl() + "/protocol/openid-connect/logout";
    }

    public String adminUsersEndpoint() {
        return baseUrl + "/admin/realms/" + realm + "/users";
    }
}
