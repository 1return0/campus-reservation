param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path $RepositoryRoot).Path
$envFile = Join-Path $repository 'deploy\.env'
if (-not (Test-Path $envFile)) { throw 'deploy/.env is required' }

$settings = @{}
foreach ($line in Get-Content $envFile) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        $settings[$matches[1]] = $matches[2]
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
foreach ($key in @('CAMPUS_JWT_HMAC_SECRET', 'CAMPUS_BOOTSTRAP_ADMIN_USERNAME',
        'CAMPUS_BOOTSTRAP_ADMIN_PASSWORD', 'RABBITMQ_HOST', 'RABBITMQ_USERNAME',
        'RABBITMQ_PASSWORD', 'CAMPUS_RESERVE_DB_URL', 'IDENTITY_DB_URL',
        'NOTIFICATION_DB_URL')) {
    if ([string]::IsNullOrWhiteSpace($settings[$key])) { throw "Missing $key in deploy/.env" }
}

$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$studentName = "e2e_$suffix"
$studentPassword = "Smoke_${suffix}!"
$resourceCode = "E2E_$suffix".ToUpperInvariant()
$exchange = "campus.e2e.$suffix"
$queue = "campus.e2e.$suffix.queue"
$dlx = "campus.e2e.$suffix.dlx"
$dlq = "campus.e2e.$suffix.dlq"
$gateway = 'http://127.0.0.1:18200'
$processes = New-Object System.Collections.ArrayList
$artifact = Join-Path $repository "target\maintenance-e2e-$suffix"
New-Item -ItemType Directory -Path $artifact -Force | Out-Null
$studentId = $null
$resourceId = $null
$slotId = $null
$reservationId = $null
$completed = $false

function Start-App([string]$module, [int]$port, [string[]]$extraArguments = @()) {
    $jar = Join-Path $repository "$module\target\$module-0.1.0-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) { throw "Missing packaged JAR: $jar" }
    $env:SERVER_PORT = "$port"
    $arguments = @('-jar', $jar) + $extraArguments
    $process = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') `
        -ArgumentList $arguments -WorkingDirectory $repository -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $artifact "$module.out.log") `
        -RedirectStandardError (Join-Path $artifact "$module.err.log")
    [void]$processes.Add($process)
}

function Wait-Ready([int]$port) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $result = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
            if ($result.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "Service on port $port did not become healthy; inspect $artifact"
}

function Jwt-Subject([string]$token) {
    $part = $token.Split('.')[1].Replace('-', '+').Replace('_', '/')
    $part = $part.PadRight([int][Math]::Ceiling($part.Length / 4) * 4, '=')
    return ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($part)) | ConvertFrom-Json).sub
}

function Call-Api([string]$method, [string]$path, [string]$token = '', $body = $null,
        [hashtable]$moreHeaders = @{}) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    foreach ($key in $moreHeaders.Keys) { $headers[$key] = $moreHeaders[$key] }
    $args = @{ Method = $method; Uri = "$gateway$path"; Headers = $headers; TimeoutSec = 15 }
    if ($null -ne $body) {
        $args['ContentType'] = 'application/json'
        $args['Body'] = ConvertTo-Json -InputObject $body -Compress
    }
    return Invoke-RestMethod @args
}

function Db-Parts([string]$prefix) {
    $uri = [Uri]($settings["${prefix}_DB_URL"] -replace '^jdbc:', '')
    return @{ Host = $uri.Host; Port = $uri.Port; Database = $uri.AbsolutePath.TrimStart('/');
        User = $settings["${prefix}_DB_USERNAME"]; Password = $settings["${prefix}_DB_PASSWORD"] }
}

function Sql([string]$prefix, [string]$statement) {
    $parts = Db-Parts $prefix
    $mysqlShell = 'D:\MySQL\MySQL\MySQL Shell 8.0\bin\mysqlsh.exe'
    if (-not (Test-Path $mysqlShell)) { throw 'mysqlsh.exe is required for verification and cleanup' }
    # mysqlsh writes its benign CLI-password warning to stderr. Exit code is
    # checked below; that warning must not become a PowerShell terminating error.
    $ErrorActionPreference = 'Continue'
    $result = & $mysqlShell --sqlc "--host=$($parts.Host)" "--port=$($parts.Port)" `
        "--user=$($parts.User)" "--password=$($parts.Password)" `
        "--database=$($parts.Database)" "--execute=$statement" 2>$null
    if ($LASTEXITCODE -ne 0) { throw "SQL failed for $prefix" }
    return $result
}

function Sql-Count([string]$prefix, [string]$statement) {
    $lines = @(Sql $prefix $statement)
    return [long]$lines[-1].Trim()
}

function Remove-RabbitTopology {
    # The application account has AMQP configure permission but not RabbitMQ
    # Management HTTP permission. Use a narrowly scoped AMQP cleanup test.
    $env:CAMPUS_RESERVE_RABBIT_LIVE_SMOKE = 'true'
    $env:CAMPUS_RESERVE_E2E_SUFFIX = $suffix
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $maven) {
        $maven = Get-ChildItem (Join-Path $env:USERPROFILE '.m2\wrapper\dists') `
            -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if (-not $maven) { throw 'mvn.cmd is required to clean the isolated AMQP topology' }
    $mavenPath = if ($maven -is [System.IO.FileInfo]) { $maven.FullName } else { $maven.Source }
    & $mavenPath -q -pl reservation-service -am `
        '-Dtest=MaintenanceTopologyCleanupSmoke' '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw "AMQP topology cleanup failed for run $suffix" }
}

try {
    $java17 = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
    if (-not (Test-Path (Join-Path $java17 'bin\java.exe'))) { throw 'Java 17 is required' }
    $env:JAVA_HOME = $java17
    $env:NACOS_DISCOVERY_ENABLED = 'false'
    $env:RESERVATION_REDIS_ENABLED = 'false'
    $env:SLOT_LIFECYCLE_ENABLED = 'false'
    $env:OUTBOX_DELIVERY_ENABLED = 'false'
    $env:RESERVATION_EVENT_CONSUMER_ENABLED = 'false'
    $preexisting = Sql-Count 'CAMPUS_RESERVE' `
        "SELECT COUNT(*) FROM reservation_outbox_event WHERE publish_status <> 'PUBLISHED';"
    if ($preexisting -ne 0) { throw 'Existing undelivered Outbox events: refusing shared scheduler smoke' }

    Start-App 'identity-service' 18201
    Wait-Ready 18201
    $env:OUTBOX_DELIVERY_ENABLED = 'true'
    $env:RESERVATION_EVENT_EXCHANGE = $exchange
    Start-App 'reservation-service' 18202
    Wait-Ready 18202
    $env:RESERVATION_EVENT_CONSUMER_ENABLED = 'true'
    Start-App 'notification-service' 18203 @(
        "--campus-reserve.messaging.exchange=$exchange",
        "--campus-reserve.messaging.queue=$queue",
        "--campus-reserve.messaging.dead-letter-exchange=$dlx",
        "--campus-reserve.messaging.dead-letter-queue=$dlq"
    )
    Wait-Ready 18203
    $env:RESERVATION_EVENT_CONSUMER_ENABLED = 'false'
    $env:IDENTITY_SERVICE_URI = 'http://127.0.0.1:18201'
    $env:RESERVATION_SERVICE_URI = 'http://127.0.0.1:18202'
    $env:NOTIFICATION_SERVICE_URI = 'http://127.0.0.1:18203'
    Start-App 'gateway-service' 18200
    Wait-Ready 18200

    $admin = Call-Api 'POST' '/api/v1/auth/login' '' @{
        username = $settings['CAMPUS_BOOTSTRAP_ADMIN_USERNAME']
        password = $settings['CAMPUS_BOOTSTRAP_ADMIN_PASSWORD']
    }
    if (-not $admin.accessToken) { throw 'Admin login did not return a token' }
    Call-Api 'POST' '/api/v1/auth/register' '' @{
        username = $studentName; displayName = 'E2E Student'; password = $studentPassword
        studentNumber = "S$suffix"
    } | Out-Null
    $student = Call-Api 'POST' '/api/v1/auth/login' '' @{
        username = $studentName; password = $studentPassword
    }
    $studentId = [long](Jwt-Subject $student.accessToken)
    $created = Call-Api 'POST' '/api/v1/admin/resources' $admin.accessToken @{
        resourceCode = $resourceCode; resourceName = "E2E venue $suffix"
        resourceType = 'MEETING_ROOM'; noShowGraceMinutes = 15
    }
    $resourceId = [long]$created.data.resourceId
    Call-Api 'PATCH' "/api/v1/admin/resources/$resourceId/publish" $admin.accessToken | Out-Null
    $start = (Get-Date).Date.AddDays(2).AddHours(10)
    $opened = Call-Api 'POST' '/api/v1/admin/slots' $admin.accessToken @{
        resourceId = $resourceId; startsAt = $start.ToString('yyyy-MM-ddTHH:mm:ss')
        endsAt = $start.AddHours(1).ToString('yyyy-MM-ddTHH:mm:ss'); capacity = 1
    }
    $slotId = [long]$opened.data.slotId
    $chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
    if (-not (Test-Path $chrome)) { throw 'Chrome is required for browser acceptance' }
    $env:CAMPUS_RESERVE_E2E_GATEWAY = $gateway
    $env:CAMPUS_RESERVE_E2E_STUDENT_NAME = $studentName
    $env:CAMPUS_RESERVE_E2E_STUDENT_PASSWORD = $studentPassword
    $env:CAMPUS_RESERVE_E2E_ADMIN_NAME = $settings['CAMPUS_BOOTSTRAP_ADMIN_USERNAME']
    $env:CAMPUS_RESERVE_E2E_ADMIN_PASSWORD = $settings['CAMPUS_BOOTSTRAP_ADMIN_PASSWORD']
    $env:CAMPUS_RESERVE_E2E_RESOURCE_CODE = $resourceCode
    $env:CAMPUS_RESERVE_E2E_SLOT_ID = "$slotId"
    $env:CAMPUS_RESERVE_E2E_REASON = "E2E maintenance $suffix"
    $env:CAMPUS_RESERVE_E2E_CDP_PORT = '19222'
    $chromeProfile = Join-Path $artifact 'chrome-profile'
    $chromeProcess = Start-Process -FilePath $chrome -PassThru -WindowStyle Hidden `
        -ArgumentList @('--headless=new', '--disable-gpu', '--no-first-run',
            '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=19222',
            "--user-data-dir=$chromeProfile", 'about:blank') `
        -RedirectStandardOutput (Join-Path $artifact 'chrome.out.log') `
        -RedirectStandardError (Join-Path $artifact 'chrome.err.log')
    [void]$processes.Add($chromeProcess)
    $priorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $browserResult = & node (Join-Path $repository 'deploy\smoke\browser-maintenance.js') 2>&1
    $ErrorActionPreference = $priorPreference
    if ($LASTEXITCODE -ne 0) { throw "Browser acceptance failed: $browserResult" }
    $reservationId = [long](($browserResult | Select-Object -Last 1 | ConvertFrom-Json).reservationId)
    if ($reservationId -lt 1) { throw 'Browser did not return a reservation ID' }
    $cancelled = Call-Api 'GET' "/api/v1/admin/slots/$slotId/cancellation" $admin.accessToken
    if ($cancelled.data.confirmedCount -ne 1 -or $cancelled.data.waitlistedCount -ne 0) {
        throw 'Maintenance cancellation counts were wrong'
    }

    $notificationFound = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        $order = Call-Api 'GET' "/api/v1/reservations/$reservationId" $student.accessToken
        $notes = Call-Api 'GET' '/api/v1/notifications/me?page=1&size=20' $student.accessToken
        $notificationFound = @($notes.data.items | Where-Object {
                $_.notificationType -eq 'RESERVATION_CANCELLED_BY_ADMIN'
            }).Count -eq 1
        if ($order.data.status -eq 'CANCELLED_BY_ADMIN' -and $notificationFound) { break }
        Start-Sleep -Seconds 1
    }
    if (-not $notificationFound -or $order.data.status -ne 'CANCELLED_BY_ADMIN') {
        throw 'Student did not observe the cancelled order and its notification'
    }
    $outboxPublished = Sql-Count 'CAMPUS_RESERVE' "SELECT COUNT(*) FROM reservation_outbox_event
        WHERE aggregate_id = $reservationId AND event_type = 'RESERVATION_CANCELLED_BY_ADMIN'
        AND publish_status = 'PUBLISHED';"
    $inboxRecorded = Sql-Count 'NOTIFICATION' "SELECT COUNT(*) FROM notification_inbox_event
        WHERE event_type = 'RESERVATION_CANCELLED_BY_ADMIN'
        AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.requesterReference')) = '$studentId';"
    if ($outboxPublished -ne 1 -or $inboxRecorded -ne 1) {
        throw "Durable event mismatch: Outbox=$outboxPublished Inbox=$inboxRecorded"
    }
    $completed = $true
    Write-Output "PASS maintenance E2E: Chrome student booking, Chrome admin cancellation, Chrome student result, Gateway HTTP, JWT, MySQL, published Outbox, RabbitMQ, Inbox; run=$suffix"
} finally {
    foreach ($process in $processes) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
    Start-Sleep -Seconds 2
    if ($studentId) {
        try {
            Sql 'NOTIFICATION' "DELETE FROM notification_inbox_event WHERE event_id IN
                (SELECT source_event_id FROM student_notification WHERE recipient_reference = '$studentId');" | Out-Null
            Sql 'NOTIFICATION' "DELETE FROM student_notification WHERE recipient_reference = '$studentId';" | Out-Null
        } catch { Write-Warning "Notification cleanup failed for run $suffix" }
    }
    if ($slotId -and $resourceId) {
        try {
            Sql 'CAMPUS_RESERVE' "DELETE FROM reservation_outbox_event WHERE aggregate_id IN
                (SELECT id FROM reservation_order WHERE slot_id = $slotId);" | Out-Null
            Sql 'CAMPUS_RESERVE' "DELETE FROM slot_cancellation WHERE slot_id = $slotId;" | Out-Null
            Sql 'CAMPUS_RESERVE' "DELETE FROM reservation_waitlist_entry WHERE slot_id = $slotId;" | Out-Null
            Sql 'CAMPUS_RESERVE' "DELETE FROM reservation_order WHERE slot_id = $slotId;" | Out-Null
            Sql 'CAMPUS_RESERVE' "DELETE FROM resource_slot WHERE id = $slotId;" | Out-Null
            Sql 'CAMPUS_RESERVE' "DELETE FROM reservable_resource WHERE id = $resourceId;" | Out-Null
        } catch { Write-Warning "Reservation cleanup failed for run $suffix" }
    }
    if ($studentId) {
        try { Sql 'IDENTITY' "DELETE FROM identity_user WHERE id = $studentId
            AND username = '$studentName' AND role_code = 'STUDENT';" | Out-Null }
        catch { Write-Warning "Identity cleanup failed for run $suffix" }
    }
    Remove-RabbitTopology
    if (-not $completed) { Write-Warning "E2E failed; process logs retained at $artifact" }
}
