[CmdletBinding()]
param(
    [switch]$SkipDocker,
    [switch]$SkipE2E
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
        & (Join-Path $repoRoot 'backend\mvnw.cmd') -f (Join-Path $repoRoot 'backend\pom.xml') --batch-mode clean verify
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
    Invoke-Stage 'Dependency audit' { npm --prefix (Join-Path $repoRoot 'frontend') audit --audit-level=high }
    if (-not $SkipDocker) {
        Invoke-Stage 'Docker Compose configuration' { docker compose --project-directory $repoRoot config --quiet }
        Invoke-Stage 'Docker health and backup/restore verification' { & (Join-Path $repoRoot 'scripts\verify-docker.ps1') }
    }
    Write-Host "`nAll requested verification stages passed."
} finally {
    Pop-Location
}
