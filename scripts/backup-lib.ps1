Set-StrictMode -Version Latest

function Get-BackupEncryptionKey {
    $secret = $env:BACKUP_ENCRYPTION_KEY
    if ([string]::IsNullOrWhiteSpace($secret) -or $secret.Length -lt 32) {
        throw 'BACKUP_ENCRYPTION_KEY 必须设置为至少 32 个字符的独立秘密。'
    }
    return $secret
}
function Protect-BackupFile {
    param(
        [Parameter(Mandatory)][string]$InputPath,
        [Parameter(Mandatory)][string]$OutputPath
    )

    $secretBytes = [Text.Encoding]::UTF8.GetBytes((Get-BackupEncryptionKey))
    $salt = [byte[]]::new(16)
    $nonce = [byte[]]::new(12)
    [Security.Cryptography.RandomNumberGenerator]::Fill($salt)
    [Security.Cryptography.RandomNumberGenerator]::Fill($nonce)
    $key = [Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2(
        $secretBytes,
        $salt,
        200000,
        [Security.Cryptography.HashAlgorithmName]::SHA256,
        32
    )
    $plainText = [IO.File]::ReadAllBytes($InputPath)
    $cipherText = [byte[]]::new($plainText.Length)
    $tag = [byte[]]::new(16)
    $aes = [Security.Cryptography.AesGcm]::new($key, 16)
    try {
        $aes.Encrypt($nonce, $plainText, $cipherText, $tag)
    }
    finally {
        $aes.Dispose()
        [Array]::Clear($key, 0, $key.Length)
        [Array]::Clear($plainText, 0, $plainText.Length)
    }

    $magic = [Text.Encoding]::ASCII.GetBytes('LABBK01')
    $stream = [IO.File]::Open($OutputPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        $stream.Write($magic)
        $stream.Write($salt)
        $stream.Write($nonce)
        $stream.Write($tag)
        $stream.Write($cipherText)
    }
    finally {
        $stream.Dispose()
        [Array]::Clear($cipherText, 0, $cipherText.Length)
    }
}

function Unprotect-BackupFile {
    param(
        [Parameter(Mandatory)][string]$InputPath,
        [Parameter(Mandatory)][string]$OutputPath
    )

    $payload = [IO.File]::ReadAllBytes($InputPath)
    if ($payload.Length -le 51) {
        throw '备份文件过短或格式无效。'
    }
    $magic = [Text.Encoding]::ASCII.GetString($payload, 0, 7)
    if ($magic -ne 'LABBK01') {
        throw '备份文件标识无效。'
    }

    $salt = $payload[7..22]
    $nonce = $payload[23..34]
    $tag = $payload[35..50]
    $cipherText = $payload[51..($payload.Length - 1)]
    $secretBytes = [Text.Encoding]::UTF8.GetBytes((Get-BackupEncryptionKey))
    $key = [Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2(
        $secretBytes,
        [byte[]]$salt,
        200000,
        [Security.Cryptography.HashAlgorithmName]::SHA256,
        32
    )
    $plainText = [byte[]]::new($cipherText.Length)
    $aes = [Security.Cryptography.AesGcm]::new($key, 16)
    try {
        $aes.Decrypt([byte[]]$nonce, [byte[]]$cipherText, [byte[]]$tag, $plainText)
        $stream = [IO.File]::Open($OutputPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try {
            $stream.Write($plainText)
        }
        finally {
            $stream.Dispose()
        }
    }
    finally {
        $aes.Dispose()
        [Array]::Clear($key, 0, $key.Length)
        [Array]::Clear($plainText, 0, $plainText.Length)
        [Array]::Clear($payload, 0, $payload.Length)
    }
}

function Assert-DatabaseIdentifier {
    param([Parameter(Mandatory)][string]$Value)
    if ($Value -notmatch '^[A-Za-z][A-Za-z0-9_]{0,62}$') {
        throw "数据库标识符 '$Value' 不合法。"
    }
}

function Invoke-CheckedDocker {
    param([Parameter(Mandatory)][string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker 命令失败，退出码 $LASTEXITCODE。"
    }
}
