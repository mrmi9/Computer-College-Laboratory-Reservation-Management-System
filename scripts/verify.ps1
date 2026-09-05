[CmdletBinding()]
param(
    [switch]$SkipDocker,
    [switch]$SkipE2E,
    [switch]$SkipPerformance
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Invoke-Stage {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][scriptblock]$Action)
    Write-Host "`n==> $Name"
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

Push-Location $repoRoot
try {
    Invoke-Stage 'Backend compile, unit tests and coverage' {
        & (Join-Path $repoRoot 'scripts\verify-backend.ps1')
    }
    Invoke-Stage 'Frontend locked install' {
        npm --prefix (Join-Path $repoRoot 'frontend') ci --ignore-scripts
    }
    Invoke-Stage 'Frontend lint' { npm --prefix (Join-Path $repoRoot 'frontend') run lint }
    Invoke-Stage 'Frontend type check' { npm --prefix (Join-Path $repoRoot 'frontend') run typecheck }
    Invoke-Stage 'Frontend unit tests' { npm --prefix (Join-Path $repoRoot 'frontend') run test }
    Invoke-Stage 'Frontend production build' { npm --prefix (Join-Path $repoRoot 'frontend') run build }
    if (-not $SkipE2E) {
        Invoke-Stage 'Playwright E2E' { npm --prefix (Join-Path $repoRoot 'frontend') run e2e }
    }
    Invoke-Stage 'Docker Compose development and production configuration' {
        docker compose --env-file (Join-Path $repoRoot '.env.example') -f (Join-Path $repoRoot 'compose.yaml') -f (Join-Path $repoRoot 'compose.dev.yaml') config --quiet
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        docker compose --env-file (Join-Path $repoRoot '.env.example') -f (Join-Path $repoRoot 'compose.yaml') -f (Join-Path $repoRoot 'compose.prod.yaml') config --quiet
    }
    Invoke-Stage 'Security checks' {
        if ($SkipDocker) {
            & (Join-Path $repoRoot 'scripts\verify-security.ps1') -SkipContainerScan
        } else {
            & (Join-Path $repoRoot 'scripts\verify-security.ps1')
        }
    }
    if (-not $SkipDocker) {
        Invoke-Stage 'Docker health, recovery and real-backend E2E' {
            & (Join-Path $repoRoot 'scripts\verify-docker.ps1') -SkipRealE2E:$SkipE2E
        }
        if (-not $SkipPerformance) {
            Invoke-Stage 'k6 10k dataset and 300-user performance' {
                & (Join-Path $repoRoot 'scripts\verify-performance.ps1')
            }
        }
    }
    Write-Host "`nAll requested verification stages passed."
} finally {
    Pop-Location
}
