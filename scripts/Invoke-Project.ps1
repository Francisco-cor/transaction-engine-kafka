[CmdletBinding()]
param(
    [ValidateSet('help', 'build', 'test', 'integration-test', 'quality', 'scan', 'up', 'down', 'logs', 'smoke', 'inspect', 'verify-invariants', 'load', 'chaos', 'clean-data')]
    [string]$Command = 'help',
    [switch]$RemoveData
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $ProjectRoot 'infra/docker-compose/docker-compose.yml'

function Invoke-Maven {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $maven = Get-Command mvn -ErrorAction SilentlyContinue
    if ($null -eq $maven) {
        throw 'Maven 3.9+ no está disponible en PATH.'
    }

    Push-Location $ProjectRoot
    try {
        & $maven.Source @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Maven terminó con código $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        throw 'Docker Desktop no está disponible en PATH.'
    }

    & $docker.Source compose -f $ComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose terminó con código $LASTEXITCODE."
    }
}

function Invoke-Smoke {
    Invoke-Compose -Arguments @('ps')
    $checks = @(
        @{ Name = 'Schema Registry'; Uri = 'http://localhost:8081/subjects' },
        @{ Name = 'Prometheus'; Uri = 'http://localhost:9090/-/ready' },
        @{ Name = 'Grafana'; Uri = 'http://localhost:3000/api/health' },
        @{ Name = 'Jaeger'; Uri = 'http://localhost:16686/' },
        @{ Name = 'Transaction service'; Uri = 'http://localhost:8080/actuator/health/readiness' },
        @{ Name = 'Ledger service'; Uri = 'http://localhost:8082/actuator/health/readiness' },
        @{ Name = 'Fraud service'; Uri = 'http://localhost:8083/actuator/health/readiness' },
        @{ Name = 'Reconciliation service'; Uri = 'http://localhost:8084/actuator/health/readiness' }
    )

    foreach ($check in $checks) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $check.Uri -TimeoutSec 5
            if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) {
                throw "HTTP $($response.StatusCode)"
            }
            Write-Host "[OK] $($check.Name)"
        }
        catch {
            throw "Smoke test falló para $($check.Name): $($_.Exception.Message)"
        }
    }

    Invoke-Compose -Arguments @('exec', '-T', 'kafka', 'kafka-topics', '--bootstrap-server', 'kafka:29092', '--list')
    Invoke-Compose -Arguments @('exec', '-T', 'postgres', 'pg_isready', '-U', 'postgres', '-d', 'transactions')
    Write-Host '[OK] Kafka y PostgreSQL responden'
}

switch ($Command) {
    'build' {
        Invoke-Maven -Arguments @('-B', '-ntp', 'verify')
    }
    'test' {
        Invoke-Maven -Arguments @('-B', '-ntp', 'test')
    }
    'integration-test' {
        Invoke-Maven -Arguments @('-B', '-ntp', '-Pintegration-tests', 'verify')
    }
    'quality' {
        Invoke-Maven -Arguments @('-B', '-ntp', 'verify')
    }
    'scan' {
        Invoke-Maven -Arguments @('-B', '-ntp', '-Psecurity-scan', 'verify', '-DskipTests')
    }
    'up' {
        Invoke-Compose -Arguments @('up', '-d')
    }
    'down' {
        Invoke-Compose -Arguments @('down', '--remove-orphans')
    }
    'logs' {
        Invoke-Compose -Arguments @('logs', '-f', '--tail', '200')
    }
    'smoke' {
        Invoke-Smoke
    }
    'inspect' {
        Invoke-Compose -Arguments @(
            'exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'transactions', '-c',
            "SELECT 'transactions' AS metric, count(*) AS value FROM transaction_schema.transactions UNION ALL SELECT 'committed', count(*) FROM transaction_schema.transactions WHERE status = 'COMMITTED' UNION ALL SELECT 'rejected', count(*) FROM transaction_schema.transactions WHERE status = 'REJECTED' UNION ALL SELECT 'ledger_entries', count(*) FROM transaction_schema.ledger_entries UNION ALL SELECT 'inbox_duplicates', COALESCE(sum(duplicate_count), 0) FROM transaction_schema.inbox_events UNION ALL SELECT 'outbox_pending', count(*) FROM transaction_schema.outbox_events WHERE status IN ('PENDING', 'CLAIMED', 'FAILED') UNION ALL SELECT 'outbox_published', count(*) FROM transaction_schema.outbox_events WHERE status = 'PUBLISHED' UNION ALL SELECT 'fraud_decisions', count(*) FROM transaction_schema.fraud_decisions UNION ALL SELECT 'fraud_pass', count(*) FROM transaction_schema.fraud_decisions WHERE decision = 'PASS' UNION ALL SELECT 'fraud_review', count(*) FROM transaction_schema.fraud_decisions WHERE decision = 'REVIEW' UNION ALL SELECT 'fraud_block', count(*) FROM transaction_schema.fraud_decisions WHERE decision = 'BLOCK' UNION ALL SELECT 'reconciliation_matched', count(*) FROM transaction_schema.reconciliation_results WHERE status = 'MATCHED' UNION ALL SELECT 'reconciliation_missing', count(*) FROM transaction_schema.reconciliation_results WHERE status = 'MISSING' UNION ALL SELECT 'reconciliation_duplicate', count(*) FROM transaction_schema.reconciliation_results WHERE status = 'DUPLICATE' UNION ALL SELECT 'reconciliation_mismatch', count(*) FROM transaction_schema.reconciliation_results WHERE status = 'MISMATCH' UNION ALL SELECT 'reconciliation_pending', count(*) FROM transaction_schema.reconciliation_results WHERE status = 'PENDING' ORDER BY metric"
        )
    }
    'verify-invariants' {
        Write-Host 'Verificando invariantes de negocio (infra/postgres/verify-invariants.sql)...'
        $sqlPath = Join-Path $ProjectRoot 'infra/postgres/verify-invariants.sql'
        if (-not (Test-Path -LiteralPath $sqlPath)) {
            throw "No se encontró $sqlPath"
        }
        # Copy SQL into container and run; fails if any invariant returns rows (except SUMMARY)
        $sqlContent = Get-Content -LiteralPath $sqlPath -Raw
        # Use psql with ON_ERROR_STOP and count violations
        $result = & $docker.Source compose -f $ComposeFile exec -T postgres psql -U postgres -d transactions -v ON_ERROR_STOP=1 -c "$sqlContent" 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "psql falló: $result"
        }
        $output = $result | Out-String
        Write-Host $output
        # Check for violation lines: any invariant except SUMMARY with data
        $violations = $output | Select-String -Pattern 'I[1-9]_' 
        if ($violations) {
            throw "Invariantes violadas:`n$($violations -join "`n")`nVer infra/postgres/verify-invariants.sql"
        }
        Write-Host '[OK] Todas las invariantes pasaron (I1-I9)'
    }
    'clean-data' {
        if (-not $RemoveData) {
            throw 'Limpieza destructiva bloqueada. Repite con -RemoveData para eliminar los volúmenes locales del proyecto.'
        }
        Invoke-Compose -Arguments @('down', '--volumes', '--remove-orphans')
        Write-Host 'Se eliminaron los volúmenes nombrados del Compose local.'
    }
    'load' {
        throw 'El comando load queda reservado para la fase 13; todavía no existe un generador de carga verificable.'
    }
    'chaos' {
        throw 'El comando chaos queda reservado para la fase 13; todavía no existe una suite de caos verificable.'
    }
    default {
        Write-Host 'Comandos: build, test, integration-test, quality, scan, up, down, logs, smoke, inspect, verify-invariants, load, chaos, clean-data'
    }
}
