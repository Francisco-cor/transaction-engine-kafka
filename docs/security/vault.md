# Vault — Transit tokenización y KV

> F3 Vault dev `1.15.6` con `kv-v2` + `transit` para `customerNote`.

## Compose dev

```powershell
docker compose -f infra/docker-compose/docker-compose.yml -f infra/docker-compose/docker-compose.vault.yml up -d vault vault-init
curl http://localhost:8200/v1/sys/health | jq .
VAULT_ADDR=http://localhost:8200 VAULT_TOKEN=root vault kv get secret/transaction-app
VAULT_ADDR=http://localhost:8200 VAULT_TOKEN=root vault write transit/encrypt/customer-note plaintext=$(echo -n "nota" | base64)
```

`infra/docker-compose/docker-compose.vault.yml:6` levanta `vault:8200` dev + `vault-init` crea `secret/transaction-app` y `transit/keys/customer-note`.

## K8s

`infra/k8s/external-secrets-vault.yaml:11` `ClusterSecretStore vault-backend` + `ExternalSecret transaction-app-password` (1h refresh). Prod usa `approle` / `k8s auth` en lugar de `token`.

## Transit tokenización

`customerNote` se tokeniza via `Vault Transit` si `VAULT_ADDR` está configurado:

```java
// TransactionApplicationService.java:186 vaultClient.encrypt(customerNote)
// libs/security/VaultTransitClient.java
vault write transit/encrypt/customer-note plaintext=$(echo -n "hola" | base64) → ciphertext vault:v1:...
vault write transit/decrypt/customer-note ciphertext=vault:v1:... → plaintext
```

Si Vault no está disponible, se guarda plaintext con flag `synthetic plain` en `metadata.vault = "plain"` (dx).

## Rotación 90d

`vault write -f transit/keys/customer-note/rotate` + `vault write transit/keys/customer-note/config min_decryption_version=1`. `ExternalSecret refreshInterval 1h` no necesita reinicio.

## Verificación

```powershell
docker compose -f infra/docker-compose/docker-compose.yml -f infra/docker-compose/docker-compose.vault.yml config --quiet
curl -H "X-Vault-Token: root" http://localhost:8200/v1/transit/keys/customer-note | jq .
```
