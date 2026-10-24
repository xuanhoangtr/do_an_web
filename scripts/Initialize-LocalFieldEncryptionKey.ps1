$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$secretDirectory = Join-Path $repositoryRoot '.local-secrets'
$secretFile = Join-Path $secretDirectory 'field-encryption.env'

New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null

if ($env:OS -eq 'Windows_NT') {
    $currentAccount = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $secretDirectory /inheritance:r /grant:r "${currentAccount}:(OI)(CI)F" /q | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not restrict access to the local secret directory.'
    }
}

if (-not (Test-Path -LiteralPath $secretFile)) {
    $keyBytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
    $keyValue = [Convert]::ToBase64String($keyBytes)
    $temporaryFile = "$secretFile.tmp"
    [System.IO.File]::WriteAllLines(
        $temporaryFile,
        @('FIELD_ENCRYPTION_ACTIVE_KEY_ID=local-v2', "FIELD_ENCRYPTION_KEYS=local-v2=$keyValue"),
        [System.Text.UTF8Encoding]::new($false)
    )
    [System.IO.File]::Move($temporaryFile, $secretFile)
    [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    Write-Host 'Created the persistent local field-encryption key.'
} else {
    Write-Host 'Reusing the existing persistent local field-encryption key.'
}

if ($env:OS -eq 'Windows_NT') {
    & icacls.exe $secretFile /inheritance:r /grant:r "${currentAccount}:F" /q | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not restrict access to the local field-encryption key.'
    }
}

$settings = @{}
foreach ($line in [System.IO.File]::ReadAllLines($secretFile)) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
        continue
    }
    $separator = $line.IndexOf('=')
    if ($separator -le 0) {
        throw 'The local field-encryption settings file is malformed; it was not changed.'
    }
    $settings[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1).Trim()
}

$activeKeyId = $settings['FIELD_ENCRYPTION_ACTIVE_KEY_ID']
$configuredKeys = $settings['FIELD_ENCRYPTION_KEYS']
if ([string]::IsNullOrWhiteSpace($activeKeyId) -or [string]::IsNullOrWhiteSpace($configuredKeys)) {
    throw 'The local field-encryption settings are incomplete; key rotation is never automatic.'
}

$activeKeyFound = $false
foreach ($entry in $configuredKeys.Split(',')) {
    $separator = $entry.IndexOf('=')
    if ($separator -le 0) {
        throw 'The local field-encryption key ring is malformed; it was not changed.'
    }
    $keyId = $entry.Substring(0, $separator).Trim()
    try {
        $decodedKey = [Convert]::FromBase64String($entry.Substring($separator + 1).Trim())
    } catch {
        throw 'The local field-encryption key ring contains invalid base64; it was not changed.'
    }
    if ($decodedKey.Length -ne 32) {
        throw 'Every local field-encryption key must be 32 bytes; the key file was not changed.'
    }
    if ($keyId -eq $activeKeyId) {
        $activeKeyFound = $true
    }
    [Array]::Clear($decodedKey, 0, $decodedKey.Length)
}
if (-not $activeKeyFound) {
    throw 'The configured local active key id is absent from its key ring; the key file was not changed.'
}

$env:FIELD_ENCRYPTION_ACTIVE_KEY_ID = $activeKeyId
$env:FIELD_ENCRYPTION_KEYS = $configuredKeys
