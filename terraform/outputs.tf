output "issuer_uri" {
  value = "${var.keycloak_url}/realms/${keycloak_realm.platinumcoin.realm}"
}

output "jwks_uri" {
  value = "${var.keycloak_url}/realms/${keycloak_realm.platinumcoin.realm}/protocol/openid-connect/certs"
}

output "harness_client_id" {
  value = keycloak_openid_client.harness.client_id
}

output "seed_user" {
  value = keycloak_user.seed.username
}
