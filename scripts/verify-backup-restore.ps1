param(
    [string]$ProjectName = 'lab-booking',
    [string]$SourceDatabase = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'lab_booking' }),
    [string]$DatabaseUser = $(if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'lab_booking' })
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'backup-lib.ps1')

Assert-DatabaseIdentifier $SourceDatabase
Assert-DatabaseIdentifier $DatabaseUser
$targetDatabase = "lab_booking_restore_$PID"
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifactDirectory = Join-Path $repositoryRoot 'artifacts/backup'
[IO.Directory]::CreateDirectory($artifactDirectory) | Out-Null
$backupPath = Join-Path $artifactDirectory ("verification-$PID.dump.enc")
$composeArguments = @('compose', '-p', $ProjectName, '-f', (Join-Path $repositoryRoot 'compose.yaml'))
$marker = "backup-verification-$PID"
$startedAt = [DateTime]::UtcNow

function Get-DatabaseFingerprint {
    param([string]$Database)
    $query = @"
select json_build_object(
  'reservation_count', (select count(*) from reservation),
  'audit_count', (select count(*) from audit_log),
  'reservation_digest', (select md5(coalesce(string_agg(reservation_no || ':' || status, ',' order by reservation_no), '')) from reservation),
  'audit_digest', (select md5(coalesce(string_agg(request_id || ':' || action || ':' || result, ',' order by id), '')) from audit_log)
)::text;
"@
    $value = & docker @composeArguments exec -T postgres psql --username $DatabaseUser --dbname $Database --tuples-only --no-align --set ON_ERROR_STOP=1 --command $query
    if ($LASTEXITCODE -ne 0) {
        throw "无法计算数据库 $Database 的指纹。"
    }
    return ($value | Out-String).Trim()
}
try {
    $insertSql = @"
insert into audit_log(actor_username, action, target_type, target_id, request_id, result, detail)
values ('backup-verifier', 'BACKUP_VERIFICATION', 'SYSTEM', '$marker', '$marker', 'SUCCESS', '{"marker":"$marker"}'::jsonb);
"@
    Invoke-CheckedDocker ($composeArguments + @('exec', '-T', 'postgres', 'psql', '--username', $DatabaseUser, '--dbname', $SourceDatabase, '--set', 'ON_ERROR_STOP=1', '--command', $insertSql))
    $before = Get-DatabaseFingerprint $SourceDatabase

    & (Join-Path $PSScriptRoot 'backup.ps1') -OutputPath $backupPath -ProjectName $ProjectName -Database $SourceDatabase -DatabaseUser $DatabaseUser | Out-Null
    $restoreStartedAt = [DateTime]::UtcNow
    & (Join-Path $PSScriptRoot 'restore.ps1') -InputPath $backupPath -TargetDatabase $targetDatabase -ProjectName $ProjectName -DatabaseUser $DatabaseUser
    $restoreSeconds = ([DateTime]::UtcNow - $restoreStartedAt).TotalSeconds
    $after = Get-DatabaseFingerprint $targetDatabase

    if ($before -ne $after) {
        throw "备份恢复指纹不一致。恢复前：$before；恢复后：$after"
    }

    $result = [ordered]@{
        verifiedAtUtc = [DateTime]::UtcNow.ToString('o')
        sourceDatabase = $SourceDatabase
        restoredDatabase = $targetDatabase
        fingerprint = ($before | ConvertFrom-Json)
        rpoSeconds = 0
        restoreSeconds = [Math]::Round($restoreSeconds, 3)
        totalSeconds = [Math]::Round(([DateTime]::UtcNow - $startedAt).TotalSeconds, 3)
        encryption = 'AES-256-GCM; PBKDF2-HMAC-SHA256 200000 iterations'
    }
    $resultPath = Join-Path $artifactDirectory 'latest-result.json'
    $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resultPath -Encoding utf8
    Write-Host "备份恢复一致性验证通过：$resultPath"
}
finally {
    & docker @composeArguments exec -T postgres dropdb --force --if-exists --username $DatabaseUser $targetDatabase 2>$null
    if ([IO.File]::Exists($backupPath)) {
        Remove-Item -LiteralPath $backupPath -Force
    }
}
