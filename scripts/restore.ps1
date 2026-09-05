param(
    [Parameter(Mandatory)][string]$InputPath,
    [Parameter(Mandatory)][string]$TargetDatabase,
    [switch]$ReplaceExisting,
    [string]$ProjectName = 'lab-booking',
    [string]$DatabaseUser = $(if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'lab_booking' })
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'backup-lib.ps1')

Assert-DatabaseIdentifier $TargetDatabase
Assert-DatabaseIdentifier $DatabaseUser
$resolvedInput = [IO.Path]::GetFullPath($InputPath)
if (-not [IO.File]::Exists($resolvedInput)) {
    throw "找不到备份文件：$resolvedInput"
}
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$temporaryDump = Join-Path ([IO.Path]::GetTempPath()) ("lab-booking-restore-{0}.dump" -f $PID)
$remoteDump = "/tmp/lab-booking-restore-$PID.dump"
$composeArguments = @('compose', '-p', $ProjectName, '-f', (Join-Path $repositoryRoot 'compose.yaml'))

try {
    Write-Host '正在验证并解密备份……'
    Unprotect-BackupFile -InputPath $resolvedInput -OutputPath $temporaryDump
    Invoke-CheckedDocker ($composeArguments + @('cp', $temporaryDump, "postgres:$remoteDump"))

    $databaseExists = & docker @composeArguments exec -T postgres psql --username $DatabaseUser --dbname postgres --tuples-only --no-align --command "select 1 from pg_database where datname = '$TargetDatabase'"
    if ($LASTEXITCODE -ne 0) {
        throw '无法检查目标数据库。'
    }
    if (($databaseExists | Out-String).Trim() -eq '1') {
        if (-not $ReplaceExisting) {
            throw "目标数据库 $TargetDatabase 已存在；如确认覆盖，请显式传入 -ReplaceExisting。"
        }
        Invoke-CheckedDocker ($composeArguments + @('exec', '-T', 'postgres', 'dropdb', '--force', '--if-exists', '--username', $DatabaseUser, $TargetDatabase))
    }

    Invoke-CheckedDocker ($composeArguments + @('exec', '-T', 'postgres', 'createdb', '--username', $DatabaseUser, $TargetDatabase))
    Invoke-CheckedDocker ($composeArguments + @(
        'exec', '-T', 'postgres', 'pg_restore',
        '--username', $DatabaseUser,
        '--dbname', $TargetDatabase,
        '--exit-on-error', '--no-owner', '--no-privileges',
        $remoteDump
    ))
    Write-Host "恢复完成：$TargetDatabase"
}
finally {
    & docker @composeArguments exec -T postgres rm -f $remoteDump 2>$null
    if ([IO.File]::Exists($temporaryDump)) {
        Remove-Item -LiteralPath $temporaryDump -Force
    }
}
