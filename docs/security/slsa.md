# Supply Chain SLSA 3 — Cosign + Rekor + Provenance + Policy Controller (F10)

> Level: SLSA 3 (hermetic, isolated, attested) para imágenes `ghcr.io/example/*:0.5.0` + `0.5.0@sha256:*`.

## 1. Pipeline

```
push main / tag v*.*.*
  ├─ mvn verify
  ├─ sbom (anchore spdx + cyclonedx → reports/sbom/)
  ├─ supply-chain (ci.yml)
  │     ├─ sigstore/cosign-installer v2.4.1
  │     ├─ docker build ghcr.io/example/transaction-service:SHA
  │     ├─ syft SBOM spdx-json reports/sbom/attest-spdx.json
  │     ├─ cosign attest --predicate spdx --keyless (Rekor https://rekor.sigstore.dev)
  │     └─ cosign sign --keyless (Fulcio https://fulcio.sigstore.dev + OIDC token.actions)
  ├─ slsa (slsa.yml)
  │     ├─ builder slsa-framework/slsa-github-generator container SLSA3
  │     ├─ provenance predicate https://slsa.dev/provenance/v0.2
  │     └─ ossf/scorecard-action results reports/scorecard.json
  └─ policy-controller (infra/k8s/policy-controller.yaml)
        └─ ClusterImagePolicy warn → enforce (keyless OIDC, ctlog Rekor, attest spdx + slsa)
```

**Digests 100% pin:** `services/*/Dockerfile:3` `maven:3.9.9@sha256`, `debian:bookworm-slim@sha256`, `distroless/java21@sha256`, `infra/docker-compose/docker-compose.exporters.yml:6` `postgres-exporter@sha256`, `redis_exporter@sha256`, `infra/helm/umbrella/values.yaml:20` `flyway@sha256`, renovate `pinDigests true` + `helm-values` customManager.

## 2. Verification local

```powershell
# Cosign verify keyless (requires ghcr image pushed)
cosign verify --keyless ghcr.io/example/transaction-service:main --certificate-identity https://github.com/example/transaction-engine-kafka/.github/workflows/ci.yml@refs/heads/main --certificate-identity-regexp '.*' --certificate-oidc-issuer https://token.actions.githubusercontent.com

# SBOM attest
cosign verify-attestation --type spdxjson --keyless ghcr.io/example/transaction-service:main

# SLSA provenance
cosign verify-attestation --type slsaprovenance --keyless ghcr.io/example/transaction-service:main

# Rekor log
rekor-cli get --log-index 0 --rekor_server https://rekor.sigstore.dev

# Digest pin check
grep -R "@sha256:" infra/docker-compose/docker-compose.yml infra/docker-compose/docker-compose.exporters.yml services/*/Dockerfile infra/helm/umbrella/values.yaml | wc -l
# debe ser >= 12 (6 Dockerfiles + 2 exporters + 1 flyway + 3 base)
```

**Sin push (PR):** `ci.yml supply-chain` hace `cosign attest --yes` dry-run `2>&1 | head -n 30` y no falla PR; solo `push main` con `id-token: write` puede firmar real a `ghcr.io` (requiere `packages: write`).

## 3. Policy Controller

`infra/k8s/policy-controller.yaml:1` `ClusterImagePolicy transaction-engine-policy` `glob ghcr.io/example/*` `authorities keyless Fulcio + Rekor` `attestations sbom-spdx + slsa-provenance` `mode warn` (cambiar a `enforce` tras dry-run).

Instalación:

```powershell
helm repo add sigstore https://sigstore.github.io/helm-charts
helm upgrade --install policy-controller sigstore/policy-controller -n cosign-system --create-namespace -f infra/k8s/policy-controller-values.yaml

kubectl apply -f infra/k8s/policy-controller.yaml
kubectl get clusterimagepolicy
kubectl get pods -n cosign-system
# Test warn: pod sin firma debe loggear pero no bloquear; con enforce debe bloquear
kubectl run test --image=nginx -n transaction-engine --dry-run=client -o yaml | kubectl apply -f -
kubectl describe clusterimagepolicy transaction-engine-policy | Select-String "mode"
```

Ver `docs/runbooks/kubernetes.md` para `helm lint` y `kubectl wait`.

## 4. Renovate 100% digests

`renovate.json:1` `pinDigests true` para `dockerfile`, `docker-compose`, `github-actions`, `helm-values` + `customManagers` para `flyway` en `values.yaml`. PR renovate actualiza digest y tag en un solo commit; CI `docker-lint` valida `docker compose config --quiet` con digests.

```powershell
# Local renovate dry-run
npx renovate --dry-run --platform local
```

## 5. Checklist SLSA3

- [ ] `grep -R @sha256: services/*/Dockerfile | wc -l` == 6 (all pinned)
- [ ] `infra/docker-compose/docker-compose.exporters.yml` digests pinned
- [ ] `infra/helm/umbrella/values.yaml` flyway digest pinned
- [ ] `ci.yml supply-chain` `cosign-installer` + `attest` + `sign` verde en `main`
- [ ] `slsa.yml` `provenance` + `scorecard` artifacts `reports/slsa.json` + `scorecard.json`
- [ ] `policy-controller.yaml` `mode warn` (prod `enforce`) y `kubectl get clusterimagepolicy` healthy
- [ ] `trivy-results.sarif` `CRITICAL 0` + `sbom spdx/cyclonedx` no-vacíos
- [ ] `renovate.json` `pinDigests true` 100% + `scorecard` badge en `README` (opcional)

## 6. Referencias

- `.github/workflows/ci.yml:143` `supply-chain` `sigstore/cosign-installer v3.6.0` + `syft` + `cosign attest/sign`
- `.github/workflows/slsa.yml:1` `slsa-framework/slsa-github-generator container SLSA3 v2.0.0` + `ossf/scorecard-action v2.4.1`
- `infra/k8s/policy-controller.yaml:1` `policy.sigstore.dev/v1beta1` `ClusterImagePolicy`
- `renovate.json:1` `pinDigests` + `customManagers` helm
- `infra/docker-compose/docker-compose.exporters.yml:1` pinned digests
- `docs/security/threat-model.md` STRIDE supply chain
