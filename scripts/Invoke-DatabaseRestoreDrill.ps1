# Restore the newest encrypted logical backup into a throwaway database and drop it.
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $root
try {
    $prompted = $false
    if (-not $env:BACKUP_ENCRYPTION_PASSPHRASE -and -not $env:BACKUP_ENCRYPTION_PASSPHRASE_FILE) {
        $secure = Read-Host "Backup passphrase" -AsSecureString
        $env:BACKUP_ENCRYPTION_PASSPHRASE = [System.Net.NetworkCredential]::new("", $secure).Password
        $prompted = $true
    }
    docker compose --env-file .env `
        -f deploy/docker-compose.yml `
        -f deploy/docker-compose.backup.yml `
        run --rm --no-deps --entrypoint /bin/sh backup-scheduler /opt/utewedding/backup/verify-restore.sh
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    if ($prompted) { Remove-Item Env:BACKUP_ENCRYPTION_PASSPHRASE -ErrorAction SilentlyContinue }
    Pop-Location
}
