param(
    [ValidateSet('baseline', 'limited')][string]$Mode = 'baseline',
    [ValidateRange(20, 500)][int]$Requests = 120,
    [string]$Concurrency = '10,50,100',
    [ValidateRange(1, 3)][int]$Repeats = 1
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
foreach ($line in Get-Content (Join-Path $repository 'deploy/.env')) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
$run = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$artifact = Join-Path $repository "target\booking-benchmark-$Mode-$run"
New-Item -ItemType Directory -Path $artifact -Force | Out-Null
$java = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot\bin\java.exe'
$env:NACOS_DISCOVERY_ENABLED = 'false'
$env:OUTBOX_DELIVERY_ENABLED = 'false'
$env:SLOT_LIFECYCLE_ENABLED = 'false'
$env:NO_SHOW_LIFECYCLE_ENABLED = 'false'
$env:RESERVATION_SERVICE_URI = 'http://127.0.0.1:18502'
$env:RESERVATION_REDIS_ENABLED = 'false'
if ([string]::IsNullOrWhiteSpace($env:RESERVATION_REDIS_HOST)) {
    $env:RESERVATION_REDIS_HOST = ([Uri]($env:CAMPUS_RESERVE_DB_URL -replace '^jdbc:', '')).Host
}
$env:RESERVATION_ADMISSION_RATE_LIMIT_ENABLED = if ($Mode -eq 'limited') { 'true' } else { 'false' }
$env:STUDY_ROOM_ADMISSION_ENABLED = $env:RESERVATION_ADMISSION_RATE_LIMIT_ENABLED
$env:RESERVATION_ADMISSION_RATE_LIMIT_WINDOW_MILLIS = '60000'
$env:RESERVATION_ADMISSION_PER_SLOT_MAX_REQUESTS = '20'
$env:STUDY_ROOM_ADMISSION_WINDOW_MILLIS = '60000'
$env:STUDY_ROOM_ADMISSION_PER_ROOM_DAY_MAX_REQUESTS = '20'
$env:BOOKING_BENCHMARK_MODE = $Mode
$env:BOOKING_BENCHMARK_REQUESTS = [string]$Requests
$env:BOOKING_BENCHMARK_CONCURRENCY = $Concurrency
$env:BOOKING_BENCHMARK_REPEATS = [string]$Repeats
$env:BOOKING_BENCHMARK_URL = 'http://127.0.0.1:18500'
$env:BOOKING_BENCHMARK_OUTPUT = Join-Path $artifact 'results.json'
$started = @()
try {
    foreach ($app in @(@('reservation-service', 18502), @('gateway-service', 18500))) {
        $module = $app[0]; $port = $app[1]
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
            throw "Benchmark port $port is occupied"
        }
        $jar = Join-Path $repository "$module/target/$module-0.1.0-SNAPSHOT.jar"
        if (-not (Test-Path $jar)) { throw "Missing JAR for $module; package first" }
        $arguments = @('-Xms256m', '-Xmx512m', '-jar', $jar, "--server.port=$port",
            '--debug=false', '--logging.level.root=INFO', '--logging.level.org.springframework=INFO',
            '--logging.level.com.campusreserve=INFO')
        $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $repository `
            -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $artifact "$module.log") `
            -RedirectStandardError (Join-Path $artifact "$module.err.log")
        $started += $process
        $ready = $false
        for ($i = 0; $i -lt 90; $i++) {
            if ($process.HasExited) { throw "$module exited; inspect $artifact" }
            try {
                $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
                if ($health.status -eq 'UP') { $ready = $true; break }
            } catch { }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw "$module not ready; inspect $artifact" }
    }
    Write-Output "Benchmark mode=$Mode artifacts=$artifact"
    & node (Join-Path $PSScriptRoot 'booking-write.js')
    if ($LASTEXITCODE -ne 0) { throw "Benchmark failed; inspect $artifact" }
} finally {
    foreach ($process in $started) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    }
}
