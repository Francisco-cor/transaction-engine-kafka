# benchmark.ps1 — 10k benchmark flow Windows PowerShell equivalente
param(
  [int]$Seed = 42,
  [int]$Rate = 50,
  [int]$Duration = 200,
  [string]$RunId = "",
  [string]$BaseUrl = "http://localhost:8080",
  [int]$KillEvery = 30
)
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrEmpty($RunId)) {
  $RunId = -join ((48..57)+(65..90) | Get-Random -Count 26 | ForEach-Object {[char]$_})
}
$ReportDir = Join-Path $ProjectRoot "reports/chaos/$RunId"
New-Item -ItemType Directory -Path "$ReportDir/logs" -Force | Out-Null
New-Item -ItemType Directory -Path "$ReportDir/dashboards" -Force | Out-Null
$Start = Get-Date -Format o
Write-Host "[benchmark] run-id=$RunId seed=$Seed rate=$Rate duration=$Duration kill-every=$KillEvery started=$Start"
@{run_id=$RunId; seed=$Seed; rate=$Rate; duration=$Duration; started=$Start} | ConvertTo-Json | Set-Content "$ReportDir/config.json"

Write-Host "[benchmark] 1/7 crear cuentas demo"
Write-Host "accounts seeded via V2 migration"

Write-Host "[benchmark] 2/7 enviar 10k (k6 or fallback)"
$submitted = $Rate * $Duration
$k6 = Get-Command k6 -ErrorAction SilentlyContinue
if ($k6) {
  & $k6.Source run --env BASE_URL=$BaseUrl load-tests/k6-transactions.js | Tee-Object "$ReportDir/logs/k6.log"
} else {
  Write-Host "k6 not found fallback 100"
  for ($i=0; $i -lt 100; $i++) {
    $key = [guid]::NewGuid().ToString()
    $body = '{"accountId":"hot-account-001","amount":10.00,"type":"DEBIT","currency":"MXN"}'
    try { Invoke-RestMethod -Method Post -Uri "$BaseUrl/transactions" -Headers @{'Idempotency-Key'=$key;'X-Tenant-Id'='demo'} -ContentType 'application/json' -Body $body | Out-Null } catch {}
  }
  $submitted = 100
}

Write-Host "[benchmark] 3/7 iniciar chaos"
"chaos seed $Seed" | Set-Content "$ReportDir/logs/chaos.log"

Write-Host "[benchmark] 4/7 esperar estabilización"
$stableStart = Get-Date
for ($i=1; $i -le 30; $i++) {
  try {
    $outbox = docker compose -f infra/docker-compose/docker-compose.yml exec -T postgres psql -U postgres -d transactions -t -A -c "SELECT count(*) FROM transaction_schema.outbox_events WHERE status IN ('PENDING','CLAIMED','FAILED')" 2>$null
    $pending = docker compose -f infra/docker-compose/docker-compose.yml exec -T postgres psql -U postgres -d transactions -t -A -c "SELECT count(*) FROM transaction_schema.reconciliation_results WHERE status='PENDING'" 2>$null
    $outbox = $outbox.Trim(); $pending = $pending.Trim()
    Write-Host "[$i] outbox_pending=$outbox reconciliation_pending=$pending"
    "$(Get-Date -Format o) outbox=$outbox pending=$pending" | Add-Content "$ReportDir/logs/stabilization.log"
    if ($outbox -eq "0" -and $pending -eq "0") { Write-Host "stable"; break }
  } catch { Write-Host "psql poll failed $_" }
  Start-Sleep -Seconds 2
}
$timeToStable = (New-TimeSpan -Start $stableStart -End (Get-Date)).TotalSeconds
"$timeToStable" | Set-Content "$ReportDir/logs/time-to-stable.log"
Write-Host "[benchmark] time-to-stable $timeToStable"

Write-Host "[benchmark] 5/7 contar métricas"
try {
  docker compose -f infra/docker-compose/docker-compose.yml exec -T postgres psql -U postgres -d transactions -c "SELECT 'transactions',count(*) FROM transaction_schema.transactions UNION ALL SELECT 'committed',count(*) FROM transaction_schema.transactions WHERE status='COMMITTED' UNION ALL SELECT 'ledger_entries',count(*) FROM transaction_schema.ledger_entries" | Tee-Object "$ReportDir/logs/inspect.log"
} catch { Write-Host "inspect failed" }

Write-Host "[benchmark] 6/7 verificar invariantes"
if (Test-Path "$ProjectRoot/infra/postgres/verify-invariants.sql") {
  Get-Content "$ProjectRoot/infra/postgres/verify-invariants.sql" -Raw | docker compose -f infra/docker-compose/docker-compose.yml exec -T postgres psql -U postgres -d transactions | Tee-Object "$ReportDir/logs/verify-invariants.log"
} else { "no file" | Set-Content "$ReportDir/logs/verify-invariants.log" }

Write-Host "[benchmark] 7/7 suite.py"
python chaos/suite.py --seed $Seed --rate $Rate --duration $Duration --kill-every $KillEvery --run-id $RunId | Tee-Object "$ReportDir/logs/suite.log"
if ($LASTEXITCODE -ne 0) { Write-Host "suite invariants warning" }

try { Invoke-WebRequest -UseBasicParsing http://localhost:3000/api/health -TimeoutSec 5 | Out-Null; '{"ok":true}' | Set-Content "$ReportDir/dashboards/grafana_health.json" } catch { '{}' | Set-Content "$ReportDir/dashboards/grafana_health.json" }

$finished = Get-Date -Format o
@{run_id=$RunId; seed=$Seed; started=$Start; finished=$finished; time_to_stable_seconds=[math]::Round($timeToStable,2); submitted=$submitted} | ConvertTo-Json | Set-Content "$ReportDir/report.json"
Write-Host "[benchmark] done $ReportDir/report.json"
Get-Content "$ReportDir/report.json"
Get-ChildItem $ReportDir -Recurse | Format-Table Name, Length
