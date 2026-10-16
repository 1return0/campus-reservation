param(
    [ValidateSet('status', 'inspect', 'replay')][string]$Action = 'status',
    [string]$EventId,
    [string]$Sha256,
    [ValidateRange(1, 100)][int]$Limit = 10,
    [switch]$ConfirmReplay
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$localConfig = Join-Path $repository 'deploy/.env'
if (Test-Path $localConfig) {
    foreach ($line in Get-Content $localConfig) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            $key = $matches[1]; $value = $matches[2]
            $allowed = $key.StartsWith('RABBITMQ_') -or $key.StartsWith('NOTIFICATION_DLQ') `
                -or $key -eq 'NOTIFICATION_SOURCE_QUEUE' -or $key -eq 'RESERVATION_EVENT_EXCHANGE'
            if ($allowed -and $null -eq [Environment]::GetEnvironmentVariable($key, 'Process')) {
                [Environment]::SetEnvironmentVariable($key, $value, 'Process')
            }
        }
    }
}
$jar = Join-Path $repository 'notification-service/target/notification-service-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $jar)) { throw 'Package notification-service and dependencies first' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$arguments = @('-Dloader.main=com.campusreserve.notification.manager.mq.dlq.NotificationDlqCommand',
    '-cp', $jar, 'org.springframework.boot.loader.launch.PropertiesLauncher', $Action, '--limit', "$Limit")
if ($EventId) { $arguments += @('--event-id', $EventId) }
if ($Sha256) { $arguments += @('--sha256', $Sha256) }
if ($ConfirmReplay) { $arguments += '--confirm-replay' }
& $java @arguments
exit $LASTEXITCODE
