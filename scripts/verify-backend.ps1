[CmdletBinding()]
param(
    [switch]$UseTestcontainers
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$maven = Join-Path $repoRoot 'backend\mvnw.cmd'

if ($UseTestcontainers) {
    & $maven -f (Join-Path $repoRoot 'backend\pom.xml') --batch-mode clean verify
    exit $LASTEXITCODE
}

$postgresBin = 'C:\Program Files\PostgreSQL\16\bin'
$initdb = Join-Path $postgresBin 'initdb.exe'
if (-not (Test-Path -LiteralPath $initdb -PathType Leaf)) {
    Write-Error 'Docker is unavailable and PostgreSQL 16 tools were not found for the external test fallback.'
}

$artifactRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot 'artifacts'))
$clusterPath = [System.IO.Path]::GetFullPath((Join-Path $artifactRoot 'postgres-test'))
if (-not $clusterPath.StartsWith($artifactRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to manage a PostgreSQL test cluster outside the repository artifact directory.'
}

$pgCtl = Join-Path $postgresBin 'pg_ctl.exe'
$createdb = Join-Path $postgresBin 'createdb.exe'
$port = $null
foreach ($candidate in 55432..55531) {
    if (-not (Get-NetTCPConnection -LocalPort $candidate -ErrorAction SilentlyContinue)) {
        $port = $candidate
        break
    }
}
if ($null -eq $port) {
    throw 'No free PostgreSQL test port was found in the 55432-55531 range.'
}
$started = $false

if (Test-Path -LiteralPath $clusterPath) {
    Remove-Item -LiteralPath $clusterPath -Recurse -Force
}
New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null

try {
    & $initdb -D $clusterPath -U lab_booking -A trust --no-locale -E UTF8
    if ($LASTEXITCODE -ne 0) { throw "initdb failed with exit code $LASTEXITCODE" }
    & $pgCtl -D $clusterPath -o "-p $port -h 127.0.0.1" -w start
    if ($LASTEXITCODE -ne 0) { throw "pg_ctl start failed with exit code $LASTEXITCODE" }
    $started = $true
    & $createdb -h 127.0.0.1 -p $port -U lab_booking lab_booking_test
    if ($LASTEXITCODE -ne 0) { throw "createdb failed with exit code $LASTEXITCODE" }

    $env:TEST_DB_URL = "jdbc:postgresql://127.0.0.1:$port/lab_booking_test"
    $env:TEST_DB_USERNAME = 'lab_booking'
    $env:TEST_DB_PASSWORD = 'test-only-password'
    & $maven -f (Join-Path $repoRoot 'backend\pom.xml') --batch-mode clean verify
    if ($LASTEXITCODE -ne 0) { throw "Backend verification failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item Env:TEST_DB_URL -ErrorAction SilentlyContinue
    Remove-Item Env:TEST_DB_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:TEST_DB_PASSWORD -ErrorAction SilentlyContinue
    if ($started) {
        & $pgCtl -D $clusterPath -m fast -w stop
    }
    if (Test-Path -LiteralPath $clusterPath) {
        Remove-Item -LiteralPath $clusterPath -Recurse -Force
    }
}
