param([string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path)

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
$studentName = "e2e_sr_$suffix"
$studentPassword = "Smoke_${suffix}!"
$resourceCode = "E2E_SR_$suffix".ToUpperInvariant()
$exchange = "campus.e2e.$suffix"
$gateway = 'http://127.0.0.1:18300'
$artifact = Join-Path $repository "target\study-room-e2e-$suffix"
New-Item -ItemType Directory -Path $artifact -Force | Out-Null
$processes = New-Object System.Collections.ArrayList
$studentId = $null
$resourceId = $null
$bookingId = $null
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
    throw "Service $port did not become healthy; inspect $artifact"
}

function Jwt-Subject([string]$token) {
    $part = $token.Split('.')[1].Replace('-', '+').Replace('_', '/')
    $part = $part.PadRight([int][Math]::Ceiling($part.Length / 4) * 4, '=')
    return ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($part)) | ConvertFrom-Json).sub
}

function Call-Api([string]$method, [string]$path, [string]$token = '', $body = $null) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    $args = @{ Method = $method; Uri = "$gateway$path"; Headers = $headers; TimeoutSec = 15 }
    if ($null -ne $body) {
        $args.ContentType = 'application/json'
        $args.Body = ConvertTo-Json -InputObject $body -Compress
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
    if (-not (Test-Path $mysqlShell)) { throw 'mysqlsh.exe is required' }
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
    $env:CAMPUS_RESERVE_RABBIT_LIVE_SMOKE = 'true'
    $env:CAMPUS_RESERVE_E2E_SUFFIX = $suffix
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $maven) {
        $maven = Get-ChildItem (Join-Path $env:USERPROFILE '.m2\wrapper\dists') `
            -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if (-not $maven) { throw 'mvn.cmd is required for AMQP cleanup' }
    $path = if ($maven -is [System.IO.FileInfo]) { $maven.FullName } else { $maven.Source }
    & $path -q -pl reservation-service -am '-Dtest=MaintenanceTopologyCleanupSmoke' `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw "AMQP topology cleanup failed for run $suffix" }
}

try {
    $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
    if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'Java 17 is required' }
    $env:NACOS_DISCOVERY_ENABLED = 'false'
    $env:RESERVATION_REDIS_ENABLED = 'false'
    $env:SLOT_LIFECYCLE_ENABLED = 'false'
    $env:OUTBOX_DELIVERY_ENABLED = 'false'
    $env:RESERVATION_EVENT_CONSUMER_ENABLED = 'false'
    $preexisting = Sql-Count 'CAMPUS_RESERVE' `
        "SELECT COUNT(*) FROM reservation_outbox_event WHERE publish_status <> 'PUBLISHED';"
    if ($preexisting -ne 0) { throw 'Existing undelivered Outbox events: refusing shared scheduler smoke' }

    # The browser must exercise the current source, not yesterday's packaged UI.
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $maven) {
        $maven = Get-ChildItem (Join-Path $env:USERPROFILE '.m2\wrapper\dists') `
            -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if (-not $maven) { throw 'mvn.cmd is required to package current sources' }
    $mavenPath = if ($maven -is [System.IO.FileInfo]) { $maven.FullName } else { $maven.Source }
    & $mavenPath -q '-DskipTests' package *> (Join-Path $artifact 'package.log')
    if ($LASTEXITCODE -ne 0) { throw "Packaging current sources failed; inspect $artifact\package.log" }

    Start-App 'identity-service' 18301
    Wait-Ready 18301
    $env:OUTBOX_DELIVERY_ENABLED = 'true'
    $env:RESERVATION_EVENT_EXCHANGE = $exchange
    Start-App 'reservation-service' 18302
    Wait-Ready 18302
    $env:RESERVATION_EVENT_CONSUMER_ENABLED = 'true'
    Start-App 'notification-service' 18303 @(
        "--campus-reserve.messaging.exchange=$exchange",
        "--campus-reserve.messaging.queue=campus.e2e.$suffix.queue",
        "--campus-reserve.messaging.dead-letter-exchange=campus.e2e.$suffix.dlx",
        "--campus-reserve.messaging.dead-letter-queue=campus.e2e.$suffix.dlq"
    )
    Wait-Ready 18303
    $env:RESERVATION_EVENT_CONSUMER_ENABLED = 'false'
    $env:IDENTITY_SERVICE_URI = 'http://127.0.0.1:18301'
    $env:RESERVATION_SERVICE_URI = 'http://127.0.0.1:18302'
    $env:NOTIFICATION_SERVICE_URI = 'http://127.0.0.1:18303'
    Start-App 'gateway-service' 18300
    Wait-Ready 18300

    $admin = Call-Api 'POST' '/api/v1/auth/login' '' @{
        username = $settings['CAMPUS_BOOTSTRAP_ADMIN_USERNAME']
        password = $settings['CAMPUS_BOOTSTRAP_ADMIN_PASSWORD'] }
    Call-Api 'POST' '/api/v1/auth/register' '' @{
        username = $studentName; displayName = 'Study-room smoke student';
        studentNumber = "S$suffix"; password = $studentPassword } | Out-Null
    $student = Call-Api 'POST' '/api/v1/auth/login' '' @{
        username = $studentName; password = $studentPassword }
    $studentId = [long](Jwt-Subject $student.accessToken)
    $resource = Call-Api 'POST' '/api/v1/admin/resources' $admin.accessToken @{
        resourceCode = $resourceCode; resourceName = "Library study room $suffix";
        resourceType = 'STUDY_ROOM'; noShowGraceMinutes = 15 }
    $resourceId = [long]$resource.data.resourceId
    Call-Api 'PATCH' "/api/v1/admin/resources/$resourceId/publish" $admin.accessToken | Out-Null
    $start = (Get-Date).Date.AddDays(1).AddHours(10)
    $day = $start.Date
    Call-Api 'POST' '/api/v1/admin/study-rooms/windows' $admin.accessToken @{
        resourceId = "$resourceId";
        startsAt = $day.AddHours(7).AddMinutes(30).ToString('yyyy-MM-ddTHH:mm:ss');
        endsAt = $day.AddHours(21).AddMinutes(30).ToString('yyyy-MM-ddTHH:mm:ss') } | Out-Null

    $chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
    if (-not (Test-Path $chrome)) { throw 'Chrome is required for browser acceptance' }
    $env:CAMPUS_RESERVE_E2E_GATEWAY = $gateway
    $env:CAMPUS_RESERVE_E2E_STUDENT_NAME = $studentName
    $env:CAMPUS_RESERVE_E2E_STUDENT_PASSWORD = $studentPassword
    $env:CAMPUS_RESERVE_E2E_ADMIN_NAME = $settings['CAMPUS_BOOTSTRAP_ADMIN_USERNAME']
    $env:CAMPUS_RESERVE_E2E_ADMIN_PASSWORD = $settings['CAMPUS_BOOTSTRAP_ADMIN_PASSWORD']
    $env:CAMPUS_RESERVE_E2E_RESOURCE_ID = "$resourceId"
    $env:CAMPUS_RESERVE_E2E_STARTS_AT = $start.ToString('yyyy-MM-ddTHH:mm:ss')
    $env:CAMPUS_RESERVE_E2E_ENDS_AT = $start.AddHours(1).ToString('yyyy-MM-ddTHH:mm:ss')
    $env:CAMPUS_RESERVE_E2E_REASON = "Study-room maintenance $suffix"
    $env:CAMPUS_RESERVE_E2E_CDP_PORT = '19223'
    $chromeProfile = Join-Path $artifact 'chrome-profile'
    $chromeProcess = Start-Process -FilePath $chrome -PassThru -WindowStyle Hidden `
        -ArgumentList @('--headless=new', '--disable-gpu', '--no-first-run', '--no-sandbox',
            '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=19223',
            "--user-data-dir=$chromeProfile", 'about:blank') `
        -RedirectStandardOutput (Join-Path $artifact 'chrome.out.log') `
        -RedirectStandardError (Join-Path $artifact 'chrome.err.log')
    [void]$processes.Add($chromeProcess)
    $priorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $browserResult = & node (Join-Path $repository 'deploy\smoke\browser-study-room.js') 2>&1
    $ErrorActionPreference = $priorPreference
    if ($LASTEXITCODE -ne 0) { throw "Browser acceptance failed: $browserResult" }
    $bookingId = [long](($browserResult | Select-Object -Last 1 | ConvertFrom-Json).bookingId)
    if ($bookingId -lt 1) { throw 'Browser did not return a booking ID' }
    $booking = Call-Api 'GET' "/api/v1/study-rooms/bookings/$bookingId" $student.accessToken
    if ($booking.data.status -ne 'CANCELLED_BY_ADMIN') { throw 'Student booking not maintenance-cancelled' }
    $published = Sql-Count 'CAMPUS_RESERVE' "SELECT COUNT(*) FROM reservation_outbox_event
        WHERE aggregate_id = $bookingId AND event_type = 'STUDY_ROOM_CANCELLED_BY_ADMIN'
          AND publish_status = 'PUBLISHED';"
    $inbox = Sql-Count 'NOTIFICATION' "SELECT COUNT(*) FROM notification_inbox_event
        WHERE event_type = 'STUDY_ROOM_CANCELLED_BY_ADMIN'
          AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.requesterReference')) = '$studentId';"
    if ($published -ne 1 -or $inbox -ne 1) {
        throw "Durable event mismatch: Outbox=$published Inbox=$inbox"
    }
    $completed = $true
    Write-Output "PASS study-room E2E: Chrome team, interval booking, admin maintenance, student result; Gateway, JWT, MySQL, published Outbox, RabbitMQ, Inbox; run=$suffix"
} finally {
    foreach ($process in $processes) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
    Start-Sleep -Seconds 2
    if ($studentId) {
        Sql 'NOTIFICATION' "DELETE FROM student_notification WHERE recipient_reference = '$studentId';" | Out-Null
        Sql 'NOTIFICATION' "DELETE FROM notification_inbox_event WHERE event_type LIKE 'STUDY_ROOM_%'
            AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.requesterReference')) = '$studentId';" | Out-Null
    }
    if ($resourceId) {
        Sql 'CAMPUS_RESERVE' "DELETE FROM reservation_outbox_event WHERE aggregate_type = 'study-room-booking'
            AND aggregate_id IN (SELECT id FROM study_room_booking WHERE resource_id = $resourceId);" | Out-Null
        Sql 'CAMPUS_RESERVE' "DELETE FROM study_room_member_bucket WHERE booking_id IN
            (SELECT id FROM study_room_booking WHERE resource_id = $resourceId);" | Out-Null
        Sql 'CAMPUS_RESERVE' "DELETE FROM study_room_booking_member WHERE booking_id IN
            (SELECT id FROM study_room_booking WHERE resource_id = $resourceId);" | Out-Null
        Sql 'CAMPUS_RESERVE' "DELETE FROM study_room_booking WHERE resource_id = $resourceId;" | Out-Null
        Sql 'CAMPUS_RESERVE' "DELETE FROM study_room_open_window WHERE resource_id = $resourceId;" | Out-Null
        Sql 'CAMPUS_RESERVE' "DELETE FROM reservable_resource WHERE id = $resourceId;" | Out-Null
    }
    if ($studentId) {
        Sql 'CAMPUS_RESERVE' "DELETE FROM study_team_member WHERE team_id IN
            (SELECT id FROM study_team WHERE leader_reference = '$studentId');" | Out-Null
        Sql 'CAMPUS_RESERVE' "DELETE FROM study_team WHERE leader_reference = '$studentId';" | Out-Null
        Sql 'IDENTITY' "DELETE FROM identity_user WHERE id = $studentId;" | Out-Null
    }
    if ($processes.Count -gt 0) { Remove-RabbitTopology }
    if (-not $completed) { Write-Warning "Study-room E2E failed; logs: $artifact" }
}
