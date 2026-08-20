[CmdletBinding()]
param(
    [ValidateSet('help', 'build', 'test', 'integration-test', 'quality', 'scan', 'up', 'down', 'logs', 'smoke', 'load', 'chaos', 'clean-data')]
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
        @{ Name = 'Jaeger'; Uri = 'http://localhost:16686/' }
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
        Write-Host 'Comandos: build, test, integration-test, quality, scan, up, down, logs, smoke, load, chaos, clean-data'
    }
}
