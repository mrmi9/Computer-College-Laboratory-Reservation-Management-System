param(
    [switch]$SkipContainerScan
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Assert-Contains {
    param([string]$Path, [string]$Pattern, [string]$Message)
    $content = [IO.File]::ReadAllText($Path)
    if ($content -notmatch $Pattern) {
        throw $Message
    }
}

Write-Host '检查生产依赖漏洞……'
& npm --prefix (Join-Path $repositoryRoot 'frontend') audit --omit=dev --audit-level=high
if ($LASTEXITCODE -ne 0) {
    throw '前端生产依赖存在未处理的 high/critical 漏洞。'
}

Write-Host '检查已跟踪文件中的私钥和实际环境文件……'
$trackedSecrets = & git -C $repositoryRoot grep -n -E 'BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY'
if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace(($trackedSecrets | Out-String))) {
    throw "发现疑似私钥：`n$($trackedSecrets | Out-String)"
}
$forbiddenFiles = & git -C $repositoryRoot ls-files '*.env' '.env' '*.pem' '*.key' '*.p12' '*.jks'
if (-not [string]::IsNullOrWhiteSpace(($forbiddenFiles | Out-String))) {
    throw "发现禁止提交的秘密文件：`n$($forbiddenFiles | Out-String)"
}

$productionConfig = Join-Path $repositoryRoot 'backend/src/main/resources/application-prod.yml'
Assert-Contains $productionConfig 'cookie-secure:\s*true' '生产配置必须启用 Secure Cookie。'
Assert-Contains $productionConfig 'include-stacktrace:\s*never' '生产错误响应必须禁用堆栈输出。'
Assert-Contains $productionConfig 'api-docs:\s*\r?\n\s+enabled:\s*false' '生产环境必须关闭 OpenAPI 端点。'
Assert-Contains $productionConfig 'swagger-ui:\s*\r?\n\s+enabled:\s*false' '生产环境必须关闭 Swagger UI。'

$composeFiles = @(
    (Join-Path $repositoryRoot 'compose.yaml'),
    (Join-Path $repositoryRoot 'compose.dev.yaml'),
    (Join-Path $repositoryRoot 'compose.prod.yaml')
)
foreach ($composeFile in $composeFiles) {
    if ([IO.File]::ReadAllText($composeFile) -match '(?m)^\s*image:\s*\S+:latest\s*$') {
        throw "Compose 文件使用了 latest 标签：$composeFile"
    }
}

if (-not $SkipContainerScan) {
    docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker 引擎不可用，无法执行 Trivy 源代码安全扫描。'
    }
    Write-Host '执行 Trivy 源代码秘密和配置扫描……'
    & docker run --rm --volume "${repositoryRoot}:/workspace:ro" aquasec/trivy:0.71.2 fs `
        --scanners secret,misconfig `
        --severity HIGH,CRITICAL `
        --exit-code 1 `
        --offline-scan `
        --skip-version-check `
        --disable-telemetry `
        --skip-dirs /workspace/frontend/node_modules `
        --skip-dirs /workspace/backend/target `
        /workspace
    if ($LASTEXITCODE -ne 0) {
        throw 'Trivy 检测到未处理的 high/critical 秘密或配置问题。'
    }
}

Write-Host '安全验证通过。'
