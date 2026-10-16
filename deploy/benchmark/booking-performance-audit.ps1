param([ValidateSet('pilot','full','safety','attribution')][string]$Mode='full')
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
foreach($line in Get-Content (Join-Path $repository 'deploy/.env')) {
    if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')
    }
}
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$maven=(Get-ChildItem (Join-Path $env:USERPROFILE '.m2/wrapper/dists') -Recurse -Filter mvn.cmd |
    Where-Object {$_.FullName -match 'apache-maven-3.9.16'} | Select-Object -First 1).FullName
if(-not $maven){throw 'Maven required'}
$env:CAMPUS_RESERVE_PERFORMANCE_AUDIT='true'
$env:AUDIT_MODE=$Mode
$env:NACOS_DISCOVERY_ENABLED='false'
$env:OUTBOX_DELIVERY_ENABLED='false'
$env:SLOT_LIFECYCLE_ENABLED='false'
$env:NO_SHOW_LIFECYCLE_ENABLED='false'
$env:RESERVATION_REDIS_ENABLED='false'
$env:RESERVATION_ADMISSION_RATE_LIMIT_ENABLED='false'
$env:STUDY_ROOM_ADMISSION_ENABLED='false'
$env:RESERVATION_SERVICE_URI='http://127.0.0.1:18602'
$process=$null;$manifest=$null
try {
    foreach($port in @(18600,18602)) {
        if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Port $port occupied"}
    }
    $raw=& node (Join-Path $PSScriptRoot 'booking-audit-environment.mjs') create
    if($LASTEXITCODE -ne 0){throw 'Could not create independent audit schema'}
    $environment=$raw | ConvertFrom-Json
    $manifest=$environment.output
    $env:AUDIT_DB_URL=$environment.url
    $env:AUDIT_ARTIFACT_DIRECTORY=Split-Path $manifest
    Write-Output "Audit uses independent schema $($environment.schema); mode=$Mode"
    $jar=Join-Path $repository 'gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar'
    $process=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') `
        -ArgumentList @('-Xms256m','-Xmx512m','-jar',$jar,'--server.port=18600','--server.address=127.0.0.1',
            '--debug=false','--logging.level.root=WARN','--logging.level.org.springframework=WARN') `
        -WorkingDirectory $repository -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $env:AUDIT_ARTIFACT_DIRECTORY 'gateway.log') `
        -RedirectStandardError (Join-Path $env:AUDIT_ARTIFACT_DIRECTORY 'gateway.err.log')
    & $maven -q -pl reservation-service -am '-Dtest=BookingPerformanceAuditLive' `
        '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xms256m -Xmx512m' test
    if($LASTEXITCODE -ne 0){throw "Audit failed; inspect $env:AUDIT_ARTIFACT_DIRECTORY"}
} finally {
    if($process -and -not $process.HasExited){Stop-Process -Id $process.Id -Force}
    if($manifest){
        & node (Join-Path $PSScriptRoot 'booking-audit-environment.mjs') cleanup $manifest
        if($LASTEXITCODE -ne 0){Write-Warning "Audit schema cleanup failed; retain manifest $manifest"}
    }
}
