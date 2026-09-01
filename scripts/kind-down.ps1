[CmdletBinding()]
param(
  [string]$ClusterName = "transaction-engine"
)
$ErrorActionPreference = "Stop"
$kind = Get-Command kind -ErrorAction SilentlyContinue
if ($null -eq $kind) { throw "kind no está en PATH." }
Write-Host "Eliminando cluster $ClusterName..."
& $kind.Source delete cluster --name $ClusterName
if ($LASTEXITCODE -ne 0) { throw "kind delete cluster falló con $LASTEXITCODE" }
Write-Host "Cluster $ClusterName eliminado"
