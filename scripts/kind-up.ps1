[CmdletBinding()]
param(
  [string]$ClusterName = "transaction-engine",
  [string]$Namespace = "transaction-engine",
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$HelmChart = Join-Path $ProjectRoot "infra/helm/umbrella"
$KindConfig = Join-Path $ProjectRoot "infra/kind/kind-config.yaml"

function Require-Command {
  param([string]$Name)
  $cmd = Get-Command $Name -ErrorAction SilentlyContinue
  if ($null -eq $cmd) { throw "Requiere $Name en PATH." }
  return $cmd
}

$kind = Require-Command "kind"
$kubectl = Require-Command "kubectl"
$helm = Require-Command "helm"
$docker = Require-Command "docker"

Write-Host "== kind up: $ClusterName =="

# 1. Create cluster if not exists
$existing = & $kind.Source get clusters 2>$null | Select-String -Pattern "^$ClusterName$"
if (-not $existing) {
  Write-Host "Creando cluster kind $ClusterName..."
  & $kind.Source create cluster --name $ClusterName --config $KindConfig
  if ($LASTEXITCODE -ne 0) { throw "kind create cluster falló" }
} else {
  Write-Host "Cluster $ClusterName ya existe, reutilizando"
}

# 2. Build images (optional) and load into kind
if (-not $SkipBuild) {
  Write-Host "Construyendo imágenes (mvn package sin tests para velocidad)..."
  Push-Location $ProjectRoot
  try {
    # Build jars first if maven available
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvn) {
      & $mvn.Source -B -ntp -pl services/transaction-service,services/ledger-service,services/fraud-service,services/reconciliation-service,services/notification-service,services/api-gateway -am package -DskipTests
    }
  } finally { Pop-Location }

  $services = @("transaction-service","ledger-service","fraud-service","reconciliation-service","notification-service","api-gateway")
  foreach ($svc in $services) {
    $image = "${svc}:dev"
    Write-Host "Building $image..."
    & $docker.Source build -f "services/$svc/Dockerfile" -t $image $ProjectRoot
    if ($LASTEXITCODE -ne 0) { throw "docker build $svc falló" }
    Write-Host "Loading $image en kind..."
    & $kind.Source load docker-image $image --name $ClusterName
    if ($LASTEXITCODE -ne 0) { throw "kind load $svc falló" }
  }
}

# 3. Helm upgrade
Write-Host "Helm lint..."
& $helm.Source lint $HelmChart -f "$HelmChart/values-dev.yaml"
if ($LASTEXITCODE -ne 0) { throw "helm lint falló" }

Write-Host "Helm upgrade --install..."
& $helm.Source upgrade --install $ClusterName $HelmChart `
  -f "$HelmChart/values-dev.yaml" `
  --create-namespace --namespace $Namespace `
  --wait --timeout 5m
if ($LASTEXITCODE -ne 0) { throw "helm upgrade falló" }

# 4. Wait for pods ready
Write-Host "Esperando pods ready..."
& $kubectl.Source wait --for=condition=ready pod -l app.kubernetes.io/instance=$ClusterName --namespace $Namespace --timeout=180s
if ($LASTEXITCODE -ne 0) {
  Write-Host "Pods no ready, mostrando estado:"
  & $kubectl.Source get pods -n $Namespace
  & $kubectl.Source describe pods -n $Namespace
  throw "kubectl wait falló"
}

# 5. Wait for migrate job
Write-Host "Esperando Job migrate..."
& $kubectl.Source wait --for=condition=complete job/$ClusterName-migrate --namespace $Namespace --timeout=120s
if ($LASTEXITCODE -ne 0) {
  Write-Host "Job migrate no completó, logs:"
  & $kubectl.Source logs job/$ClusterName-migrate -n $Namespace
}

Write-Host "== kind up OK =="
& $kubectl.Source get pods -n $Namespace
& $kubectl.Source get pdb -n $Namespace
& $kubectl.Source get hpa -n $Namespace
