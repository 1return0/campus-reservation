param()
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repository
foreach ($line in Get-Content (Join-Path $repository 'deploy/.env')) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$maven = (Get-ChildItem (Join-Path $env:USERPROFILE '.m2/wrapper/dists') -Recurse -Filter mvn.cmd |
    Where-Object { $_.FullName -match 'apache-maven-3.9.16' } | Select-Object -First 1).FullName
if (-not $maven) { throw 'Maven required' }
$manifest = $null
try {
    $raw = & node (Join-Path $PSScriptRoot 'booking-audit-environment.mjs') create
    if ($LASTEXITCODE -ne 0) { throw 'Independent schema creation failed' }
    $environment = $raw | ConvertFrom-Json
    $manifest = $environment.output
    $env:AUDIT_DB_URL = $environment.url
    $env:AUDIT_ARTIFACT_DIRECTORY = Split-Path $manifest
    $env:ASYNC_LIVE_RUN = $environment.run
    $env:CAMPUS_RESERVE_ASYNC_LIVE = 'true'
    Write-Output "Async validation: isolated schema $($environment.schema), isolated MQ prefix campus.async.test.$($environment.run)"
    & $maven -q -pl reservation-service -am '-Dtest=LectureAsyncLiveTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw "Live validation failed; inspect $env:AUDIT_ARTIFACT_DIRECTORY and Surefire reports" }
} finally {
    if ($manifest) {
        & node (Join-Path $PSScriptRoot 'booking-audit-environment.mjs') cleanup $manifest
        if ($LASTEXITCODE -ne 0) { Write-Warning "Isolated schema cleanup failed; retain $manifest" }
    }
}
