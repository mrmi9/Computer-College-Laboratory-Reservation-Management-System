param(
    [string]$OutputPath,
    [string]$ProjectName = 'lab-booking',
    [string]$Database = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'lab_booking' }),
    [string]$DatabaseUser = $(if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'lab_booking' })
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'backup-lib.ps1')

Assert-DatabaseIdentifier $Database
Assert-DatabaseIdentifier $DatabaseUser
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $backupDirectory = Join-Path $repositoryRoot 'backups'
    [IO.Directory]::CreateDirectory($backupDirectory) | Out-Null
    $OutputPath = Join-Path $backupDirectory ("lab-booking-{0}.dump.enc" -f [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
}
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
if ([IO.File]::Exists($resolvedOutput)) {
    throw "目标文件已存在：$resolvedOutput"
}
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($resolvedOutput)) | Out-Null

$temporaryDump = Join-Path ([IO.Path]::GetTempPath()) ("lab-booking-backup-{0}.dump" -f $PID)
$remoteDump = "/tmp/lab-booking-backup-$PID.dump"
$composeArguments = @('compose', '-p', $ProjectName, '-f', (Join-Path $repositoryRoot 'compose.yaml'))

try {
    Write-Host "正在从数据库 $Database 创建一致性备份……"
    Invoke-CheckedDocker ($composeArguments + @(
        'exec', '-T', 'postgres', 'pg_dump',
        '--username', $DatabaseUser,
        '--dbname', $Database,
        '--format=custom', '--compress=9', '--no-owner', '--no-privileges',
        '--file', $remoteDump
    ))
    Invoke-CheckedDocker ($composeArguments + @('cp', "postgres:$remoteDump", $temporaryDump))
    Protect-BackupFile -InputPath $temporaryDump -OutputPath $resolvedOutput
    Write-Host '备份已使用 AES-256-GCM 加密。'
    Write-Output $resolvedOutput
}
finally {
    & docker @composeArguments exec -T postgres rm -f $remoteDump 2>$null
    if ([IO.File]::Exists($temporaryDump)) {
        Remove-Item -LiteralPath $temporaryDump -Force
    }
}
