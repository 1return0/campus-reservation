param(
    [ValidateSet('no-cache', 'redis-cache')][string]$Mode = 'no-cache',
    [int]$Requests = 400,
    [int]$Concurrency = 16,
    [int]$Warmup = 40
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envFile = Join-Path $repository 'deploy\.env'
if (-not (Test-Path $envFile)) { throw 'deploy/.env is required' }
foreach ($line in Get-Content $envFile) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
if ([string]::IsNullOrWhiteSpace($env:CAMPUS_JWT_HMAC_SECRET)) { throw 'JWT secret is missing' }
$hostFromDatabase = ([Uri]($env:CAMPUS_RESERVE_DB_URL -replace '^jdbc:', '')).Host
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'Java 17 is required' }
$env:NACOS_DISCOVERY_ENABLED = 'false'
$env:OUTBOX_DELIVERY_ENABLED = 'false'
$env:SLOT_LIFECYCLE_ENABLED = 'false'
$env:NO_SHOW_LIFECYCLE_ENABLED = 'false'
$env:RESERVATION_REDIS_ENABLED = if ($Mode -eq 'redis-cache') { 'true' } else { 'false' }
$env:RESERVATION_REDIS_HOST = $hostFromDatabase
$env:RESERVATION_SERVICE_URI = 'http://127.0.0.1:18402'
$env:IDENTITY_SERVICE_URI = 'http://127.0.0.1:18401'
$env:NOTIFICATION_SERVICE_URI = 'http://127.0.0.1:18403'
$env:CAMPUS_BENCHMARK_URL = 'http://127.0.0.1:18400/api/v1/resources'
$env:CAMPUS_BENCHMARK_REQUESTS = [string]$Requests
$env:CAMPUS_BENCHMARK_CONCURRENCY = [string]$Concurrency
$env:CAMPUS_BENCHMARK_WARMUP = [string]$Warmup
$env:CAMPUS_BENCHMARK_MODE = $Mode

$runId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$artifact = Join-Path $repository "target\catalog-benchmark-$runId"
New-Item -ItemType Directory -Path $artifact -Force | Out-Null
$started = New-Object System.Collections.ArrayList

function Start-App([string]$module, [int]$port) {
    $jar = Join-Path $repository "$module\target\$module-0.1.0-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) { throw "Package the project first: $jar" }
    $env:SERVER_PORT = [string]$port
    $process = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') `
        -ArgumentList @('-jar', $jar) -WorkingDirectory $repository -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $artifact "$module.out.log") `
        -RedirectStandardError (Join-Path $artifact "$module.err.log")
    [void]$started.Add($process)
}

function Wait-Ready([int]$port) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "Service $port did not become healthy; inspect $artifact"
}

try {
    foreach ($port in @(18400, 18402)) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
            throw "Port $port is already in use"
        }
    }
    Start-App 'reservation-service' 18402
    Wait-Ready 18402
    Start-App 'gateway-service' 18400
    Wait-Ready 18400
    & node (Join-Path $PSScriptRoot 'catalog-read.js')
    if ($LASTEXITCODE -ne 0) { throw 'Catalog benchmark failed' }
} finally {
    foreach ($process in $started) {
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
}
