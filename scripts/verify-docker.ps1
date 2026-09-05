param(
    [string]$ProjectName = 'lab-booking-verify',
    [switch]$KeepEnvironment,
    [switch]$SkipImageScan,
    [switch]$SkipRealE2E
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifactDirectory = Join-Path $repositoryRoot 'artifacts/docker'
[IO.Directory]::CreateDirectory($artifactDirectory) | Out-Null
$composeFiles = @(
    '-f', (Join-Path $repositoryRoot 'compose.yaml'),
    '-f', (Join-Path $repositoryRoot 'compose.dev.yaml')
)
$composeBase = @('compose', '-p', $ProjectName) + $composeFiles

function Invoke-CheckedDocker {
    param([string[]]$Arguments, [string]$FailureMessage)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}

function Invoke-Http {
    param([string]$Uri, [string]$Method = 'GET', [string]$Body)
    $handler = [Net.Http.HttpClientHandler]::new()
    $client = [Net.Http.HttpClient]::new($handler)
    try {
        $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Uri)
        if ($PSBoundParameters.ContainsKey('Body')) {
            $request.Content = [Net.Http.StringContent]::new($Body, [Text.Encoding]::UTF8, 'application/json')
        }
        return $client.Send($request)
    }
    finally {
        $client.Dispose()
        $handler.Dispose()
    }
}

docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker 引擎不可用，无法执行容器健康与恢复验证。'
}

$savedEnvironment = @{}
foreach ($name in @('DB_NAME', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET', 'BACKUP_ENCRYPTION_KEY', 'CORS_ALLOWED_ORIGINS', 'SPRING_PROFILES_ACTIVE', 'PUBLIC_HTTP_PORT', 'POSTGRES_DEV_PORT')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$env:DB_NAME = 'lab_booking'
$env:DB_USERNAME = 'lab_booking'
$env:DB_PASSWORD = 'docker-verification-database-password'
$env:JWT_SECRET = 'docker-verification-jwt-secret-at-least-32-bytes'
$env:BACKUP_ENCRYPTION_KEY = 'docker-verification-backup-key-at-least-32-bytes'
$env:CORS_ALLOWED_ORIGINS = 'http://127.0.0.1:58088'
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:PUBLIC_HTTP_PORT = '58088'
$env:POSTGRES_DEV_PORT = '55433'

$startedAt = [DateTime]::UtcNow
try {
    Invoke-CheckedDocker ($composeBase + @('config', '--quiet')) 'Docker Compose 开发配置解析失败。'
    Invoke-CheckedDocker @(
        'compose', '-p', $ProjectName,
        '-f', (Join-Path $repositoryRoot 'compose.yaml'),
        '-f', (Join-Path $repositoryRoot 'compose.prod.yaml'),
        'config', '--quiet'
    ) 'Docker Compose 生产配置解析失败。'

    Invoke-CheckedDocker ($composeBase + @('down', '--volumes', '--remove-orphans')) '无法清理隔离的 Docker 验证环境。'
    Invoke-CheckedDocker ($composeBase + @('up', '--detach', '--build', '--wait', 'postgres', 'backend', 'frontend')) 'Docker 服务构建或健康检查失败。'

    $backendUid = (& docker @composeBase exec -T backend id -u | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $backendUid -eq '0' -or $backendUid -ne '10001') {
        throw "后端容器未使用预期的非 root 用户：$backendUid"
    }
    $frontendUid = (& docker @composeBase exec -T frontend id -u | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $frontendUid -eq '0') {
        throw "前端容器仍以 root 用户运行：$frontendUid"
    }

    $postgresContainer = (& docker @composeBase ps -q postgres | Out-String).Trim()
    $portJson = (& docker inspect $postgresContainer --format '{{json .NetworkSettings.Ports}}' | Out-String).Trim() | ConvertFrom-Json
    foreach ($property in $portJson.PSObject.Properties) {
        foreach ($binding in @($property.Value)) {
            if ($null -ne $binding -and $binding.HostIp -notin @('127.0.0.1', '::1')) {
                throw "PostgreSQL 端口暴露到非回环地址：$($binding.HostIp)"
            }
        }
    }

    $health = Invoke-Http -Uri 'http://127.0.0.1:58088/healthz'
    if ([int]$health.StatusCode -ne 200) { throw 'Nginx 健康检查未返回 200。' }
    $api = Invoke-Http -Uri 'http://127.0.0.1:58088/api/v1/auth/login' -Method 'POST' -Body '{}'
    $apiBody = $api.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if ([int]$api.StatusCode -ne 400 -or $apiBody -notmatch 'requestId') {
        throw "Nginx /api 反向代理未返回标准 API 错误：$([int]$api.StatusCode) $apiBody"
    }
    $management = Invoke-Http -Uri 'http://127.0.0.1:58088/actuator/health'
    $managementType = $management.Content.Headers.ContentType.MediaType
    if ($managementType -eq 'application/vnd.spring-boot.actuator.v3+json') {
        throw '管理端点被 Nginx 直接公开。'
    }

    Invoke-CheckedDocker ($composeBase + @('exec', '-T', 'backend', 'curl', '--fail', '--silent', 'http://127.0.0.1:8080/actuator/prometheus')) '容器内部 Prometheus 指标端点不可用。'
    Invoke-CheckedDocker ($composeBase + @('exec', '-T', 'backend', 'curl', '--fail', '--silent', 'http://127.0.0.1:8080/v3/api-docs')) '开发环境 OpenAPI 端点不可用。'

    & (Join-Path $PSScriptRoot 'verify-backup-restore.ps1') -ProjectName $ProjectName -SourceDatabase lab_booking -DatabaseUser lab_booking
    if ($LASTEXITCODE -ne 0) { throw '备份恢复一致性验证失败。' }

    if (-not $SkipRealE2E) {
        Invoke-CheckedDocker ($composeBase + @(
            'exec', '-T', 'postgres', 'psql', '--username', 'lab_booking', '--dbname', 'lab_booking',
            '--set', 'ON_ERROR_STOP=1', '--command',
            "update sys_user set must_change_password = false where username in ('student01','teacher01','labadmin01','sysadmin01')"
        )) '无法准备真实 E2E 演示账号。'
        $realConfig = Join-Path $repositoryRoot 'frontend/playwright.real.config.ts'
        if (-not [IO.File]::Exists($realConfig)) { throw '缺少真实后端 Playwright 配置。' }
        $env:E2E_BASE_URL = 'http://127.0.0.1:58088'
        & npm --prefix (Join-Path $repositoryRoot 'frontend') run e2e:real
        if ($LASTEXITCODE -ne 0) { throw '真实后端 Playwright E2E 失败。' }
    }

    if (-not $SkipImageScan) {
        foreach ($image in @("${ProjectName}-backend", "${ProjectName}-frontend")) {
            & docker run --rm `
                --volume '/var/run/docker.sock:/var/run/docker.sock' `
                aquasec/trivy:0.71.2 image `
                --severity HIGH,CRITICAL `
                --ignore-unfixed `
                --exit-code 1 `
                --offline-scan `
                --skip-version-check `
                --disable-telemetry `
                $image
            if ($LASTEXITCODE -ne 0) { throw "运行时镜像存在未处理的 high/critical 漏洞：$image" }
        }
    }

    $result = [ordered]@{
        verifiedAtUtc = [DateTime]::UtcNow.ToString('o')
        project = $ProjectName
        services = @('postgres', 'backend', 'frontend')
        flywayEmptyDatabase = 'passed by fresh named volume startup'
        backendUserId = $backendUid
        frontendUserId = $frontendUid
        nginxApiProxy = 'passed'
        managementEndpointPublic = $false
        backupRestore = 'passed with reservation and audit fingerprints'
        realE2E = $(if ($SkipRealE2E) { 'skipped by explicit switch' } else { 'passed' })
        imageScan = $(if ($SkipImageScan) { 'skipped by explicit switch' } else { 'passed' })
        durationSeconds = [Math]::Round(([DateTime]::UtcNow - $startedAt).TotalSeconds, 3)
    }
    $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $artifactDirectory 'latest-result.json') -Encoding utf8
    Write-Host 'Docker 构建、健康、网络、非 root、代理、恢复和真实 E2E 验证通过。'
}
finally {
    Remove-Item Env:E2E_BASE_URL -ErrorAction SilentlyContinue
    if (-not $KeepEnvironment) {
        & docker @composeBase down --volumes --remove-orphans
    }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
