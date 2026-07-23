variable "keycloak_url" {
  description = "URL base do Keycloak"
  type        = string
  default     = "http://localhost:8080"
}

variable "keycloak_admin_username" {
  description = "Bootstrap admin do Keycloak (realm master) usado só pelo Terraform"
  type        = string
  default     = "admin"
}

variable "keycloak_admin_password" {
  type      = string
  default   = "admin"
  sensitive = true
}

# Segredo do client de service account do auth-service. Default só para dev local;
# em produção viria de um secrets manager (fora de escopo da POC — ver docs/dod.md).
variable "auth_service_admin_client_secret" {
  type      = string
  default   = "platinumcoin-local-dev-secret"
  sensitive = true
}

variable "seed_user_password" {
  type      = string
  default   = "Seed@12345"
  sensitive = true
}
