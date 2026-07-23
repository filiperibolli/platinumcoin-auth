# Realm platinumcoin: realm + user profile + client scope `account` (claim accountId)
# + client de harness (Direct Access Grants) + service account do auth-service + usuário seed.

resource "keycloak_realm" "platinumcoin" {
  realm   = "platinumcoin"
  enabled = true

  # Fatia 2 — ciclo de sessão: access curto (validação JWKS é offline, o TTL é o
  # limite do dano), refresh maior com rotação; reuso de refresh revoga a sessão.
  access_token_lifespan    = "5m0s"
  sso_session_idle_timeout = "30m0s"
  sso_session_max_lifespan = "10h0m0s"
  revoke_refresh_token     = true
  refresh_token_max_reuse  = 0
}

# Keycloak 26.x usa Declarative User Profile por default: atributo não declarado aqui
# é ignorado silenciosamente (gotcha resolvido nesta fatia — ver PLAN.md).
resource "keycloak_realm_user_profile" "platinumcoin" {
  realm_id = keycloak_realm.platinumcoin.id

  attribute {
    name         = "username"
    display_name = "$${username}"

    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }

    validator {
      name = "length"
      config = {
        min = "3"
        max = "255"
      }
    }
  }

  attribute {
    name         = "email"
    display_name = "$${email}"

    required_for_roles = ["user"]

    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }

    validator {
      name   = "email"
      config = {}
    }
  }

  attribute {
    name         = "firstName"
    display_name = "$${firstName}"

    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }

    validator {
      name = "length"
      config = {
        max = "255"
      }
    }
  }

  attribute {
    name         = "lastName"
    display_name = "$${lastName}"

    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }

    validator {
      name = "length"
      config = {
        max = "255"
      }
    }
  }

  # Identidade de conta da fintech: só o admin (Admin API do auth-service) escreve.
  attribute {
    name         = "accountId"
    display_name = "Account ID"

    permissions {
      view = ["admin"]
      edit = ["admin"]
    }

    validator {
      name = "pattern"
      config = {
        pattern       = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        error-message = "accountId deve ser um UUID"
      }
    }
  }

  attribute {
    name         = "cpf"
    display_name = "CPF"

    permissions {
      view = ["admin", "user"]
      edit = ["admin"]
    }

    validator {
      name = "pattern"
      config = {
        pattern       = "^\\d{11}$"
        error-message = "CPF deve ter 11 dígitos"
      }
    }
  }
}

# --- Client scope `account`: leva o accountId para dentro do access token ---

resource "keycloak_openid_client_scope" "account" {
  realm_id               = keycloak_realm.platinumcoin.id
  name                   = "account"
  description            = "Claims de conta PlatinumCoin (accountId)"
  include_in_token_scope = true
}

# Gotcha do 26.x: sem add_to_access_token o claim só sai no ID token/userinfo.
resource "keycloak_openid_user_attribute_protocol_mapper" "account_id" {
  realm_id        = keycloak_realm.platinumcoin.id
  client_scope_id = keycloak_openid_client_scope.account.id
  name            = "accountId"

  user_attribute      = "accountId"
  claim_name          = "accountId"
  claim_value_type    = "String"
  add_to_id_token     = true
  add_to_access_token = true
  add_to_userinfo     = true
}

# --- Fatia 3: role `customer` + audiência do payments (ADR-006) ---

resource "keycloak_role" "customer" {
  realm_id    = keycloak_realm.platinumcoin.id
  name        = "customer"
  description = "Cliente da fintech: pode enviar Pix"
}

# Scope que carimba o `aud` do payments no access token. Audiência custom (string),
# sem client registrado para o payments: ele é só consumidor, não participa do OIDC.
resource "keycloak_openid_client_scope" "payments" {
  realm_id               = keycloak_realm.platinumcoin.id
  name                   = "payments"
  description            = "Audiência do platinumcoin-payments"
  include_in_token_scope = true
}

resource "keycloak_openid_audience_protocol_mapper" "payments_audience" {
  realm_id        = keycloak_realm.platinumcoin.id
  client_scope_id = keycloak_openid_client_scope.payments.id
  name            = "payments-audience"

  included_custom_audience = "platinumcoin-payments"
  add_to_access_token      = true
  add_to_id_token          = false
}

# --- Client de harness: login por Direct Access Grants (dev/teste; prod = auth-code+PKCE, ADR-008) ---

resource "keycloak_openid_client" "harness" {
  realm_id  = keycloak_realm.platinumcoin.id
  client_id = "platinumcoin-harness"
  name      = "Harness (login dev/teste)"

  access_type                  = "PUBLIC"
  direct_access_grants_enabled = true
  standard_flow_enabled        = false
}

resource "keycloak_openid_client_default_scopes" "harness" {
  realm_id  = keycloak_realm.platinumcoin.id
  client_id = keycloak_openid_client.harness.id

  default_scopes = [
    "profile",
    "email",
    "roles",
    "web-origins",
    "basic",
    "acr",
    keycloak_openid_client_scope.account.name,
    keycloak_openid_client_scope.payments.name,
  ]
}

# --- Service account do auth-service: menor privilégio (só manage/view-users deste realm) ---

resource "keycloak_openid_client" "auth_service_admin" {
  realm_id  = keycloak_realm.platinumcoin.id
  client_id = "auth-service-admin"
  name      = "auth-service (Admin API, service account)"

  access_type                  = "CONFIDENTIAL"
  client_secret                = var.auth_service_admin_client_secret
  service_accounts_enabled     = true
  standard_flow_enabled        = false
  direct_access_grants_enabled = false
}

data "keycloak_openid_client" "realm_management" {
  realm_id  = keycloak_realm.platinumcoin.id
  client_id = "realm-management"
}

resource "keycloak_openid_client_service_account_role" "manage_users" {
  realm_id                = keycloak_realm.platinumcoin.id
  service_account_user_id = keycloak_openid_client.auth_service_admin.service_account_user_id
  client_id               = data.keycloak_openid_client.realm_management.id
  role                    = "manage-users"
}

resource "keycloak_openid_client_service_account_role" "view_users" {
  realm_id                = keycloak_realm.platinumcoin.id
  service_account_user_id = keycloak_openid_client.auth_service_admin.service_account_user_id
  client_id               = data.keycloak_openid_client.realm_management.id
  role                    = "view-users"
}

# --- Usuário seed (CPF de exemplo válido pelo dígito verificador) ---

resource "keycloak_user" "seed" {
  realm_id       = keycloak_realm.platinumcoin.id
  username       = "alice@platinumcoin.dev"
  email          = "alice@platinumcoin.dev"
  email_verified = true
  first_name     = "Alice"
  last_name      = "Platinum"
  enabled        = true

  attributes = {
    accountId = "7f8b1e7e-2a4d-4d6e-9c1a-5b3f2a9d0c11"
    cpf       = "39053344705"
  }

  initial_password {
    value     = var.seed_user_password
    temporary = false
  }

  depends_on = [keycloak_realm_user_profile.platinumcoin]
}

# Não-exaustivo: só garante a role customer sem gerenciar as default roles do usuário.
resource "keycloak_user_roles" "seed" {
  realm_id   = keycloak_realm.platinumcoin.id
  user_id    = keycloak_user.seed.id
  exhaustive = false

  role_ids = [keycloak_role.customer.id]
}
