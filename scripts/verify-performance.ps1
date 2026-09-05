param(
    [string]$ProjectName = 'lab-booking-performance',
    [switch]$KeepEnvironment
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$composeFiles = @(
    '-f', (Join-Path $repositoryRoot 'compose.yaml'),
    '-f', (Join-Path $repositoryRoot 'compose.dev.yaml')
)
$artifactDirectory = Join-Path $repositoryRoot 'artifacts/performance'
[IO.Directory]::CreateDirectory($artifactDirectory) | Out-Null
$summaryPath = Join-Path $artifactDirectory 'summary.json'
[IO.File]::WriteAllText($summaryPath, '')
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    & chmod 0666 $summaryPath
    if ($LASTEXITCODE -ne 0) { throw '无法为 k6 性能汇总文件设置写权限。' }
}
$memoryBytes = $null
if (Test-Path -LiteralPath '/proc/meminfo') {
    $memoryLine = Get-Content -LiteralPath '/proc/meminfo' | Where-Object { $_ -match '^MemTotal:' } | Select-Object -First 1
    if ($memoryLine -match '^MemTotal:\s+(\d+)\s+kB') {
        $memoryBytes = [int64]$Matches[1] * 1KB
    }
}
$environmentEvidence = [ordered]@{
    recordedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    os = [Runtime.InteropServices.RuntimeInformation]::OSDescription
    architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
    processorCount = [Environment]::ProcessorCount
    memoryBytes = $memoryBytes
    dataset = [ordered]@{ users = 10000; labs = 200 }
    workload = [ordered]@{
        maximumVirtualUsers = 300
        rampUpSeconds = 20
        steadyStateSeconds = 30
        rampDownSeconds = 10
        thinkTimeSecondsBetweenRequests = 3
    }
    thresholdsMilliseconds = [ordered]@{ queryP95 = 500; reservationP95 = 1000 }
}
[IO.File]::WriteAllText(
    (Join-Path $artifactDirectory 'environment.json'),
    ($environmentEvidence | ConvertTo-Json -Depth 5)
)

docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker 引擎不可用，无法执行 300 并发性能验证。'
}
$savedEnvironment = @{}
foreach ($name in @(
    'DB_NAME', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET', 'CORS_ALLOWED_ORIGINS',
    'SPRING_PROFILES_ACTIVE', 'RATE_LIMIT_MAX_PER_MINUTE', 'LOGIN_RATE_LIMIT_MAX_PER_MINUTE',
    'DB_POOL_MAX_SIZE', 'DB_POOL_MIN_IDLE', 'TOMCAT_MAX_THREADS', 'TOMCAT_MIN_SPARE_THREADS',
    'TOMCAT_ACCEPT_COUNT'
)) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$env:DB_NAME = 'lab_booking'
$env:DB_USERNAME = 'lab_booking'
$env:DB_PASSWORD = 'performance-database-password'
$env:JWT_SECRET = 'performance-only-jwt-secret-32-bytes-minimum'
$env:CORS_ALLOWED_ORIGINS = 'http://127.0.0.1:8088'
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:RATE_LIMIT_MAX_PER_MINUTE = '20000'
$env:LOGIN_RATE_LIMIT_MAX_PER_MINUTE = '20000'
$env:DB_POOL_MAX_SIZE = '20'
$env:DB_POOL_MIN_IDLE = '10'
$env:TOMCAT_MAX_THREADS = '200'
$env:TOMCAT_MIN_SPARE_THREADS = '20'
$env:TOMCAT_ACCEPT_COUNT = '300'

$composeBase = @('compose', '-p', $ProjectName) + $composeFiles
$remoteSeed = '/tmp/lab-booking-performance-seed.sql'
try {
    & docker @composeBase down --volumes --remove-orphans
    if ($LASTEXITCODE -ne 0) { throw '无法清理隔离的性能测试环境。' }
    & docker @composeBase up --detach --build --wait postgres backend frontend
    if ($LASTEXITCODE -ne 0) { throw '性能测试环境启动失败。' }

    & docker @composeBase cp (Join-Path $repositoryRoot 'performance/seed.sql') "postgres:$remoteSeed"
    if ($LASTEXITCODE -ne 0) { throw '性能种子数据复制失败。' }
    & docker @composeBase exec -T postgres psql --username lab_booking --dbname lab_booking --file $remoteSeed
    if ($LASTEXITCODE -ne 0) { throw '性能种子数据加载失败。' }

    & docker @composeBase --profile performance run --rm k6 run --summary-export=/results/summary.json /scripts/load.js
    if ($LASTEXITCODE -ne 0) { throw 'k6 性能阈值未通过。' }
    Write-Host "性能验证通过，结果：$artifactDirectory\summary.json"
}
finally {
    & docker @composeBase exec -T postgres rm -f $remoteSeed 2>$null
    if (-not $KeepEnvironment) {
        & docker @composeBase down --volumes --remove-orphans
    }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
