<#
.SYNOPSIS
  Generates the server keystore (server.p12) and the client trust certificate (server.crt) with keytool.
.PARAMETER OutDir
  Output root. Creates <OutDir>/server/server.p12 and <OutDir>/client/server.crt.
.PARAMETER AddIp
  Optional LAN IP added to the SAN so remote clients can verify the hostname.
.PARAMETER StartDate
  Optional keytool -startdate value (e.g. "-10d") for manual expired-certificate testing.
.NOTES
  The keystore password is read from $env:CHAT_CERT_PASSWORD. Output must never be committed.
#>
param(
    [string]$OutDir = "certificates",
    [string]$AddIp,
    [string]$StartDate
)

$ErrorActionPreference = "Stop"

$pw = $env:CHAT_CERT_PASSWORD
if ([string]::IsNullOrWhiteSpace($pw)) {
    throw "Thiếu CHAT_CERT_PASSWORD"
}

$serverDir = Join-Path $OutDir "server"
$clientDir = Join-Path $OutDir "client"
$p12 = Join-Path $serverDir "server.p12"
$crt = Join-Path $clientDir "server.crt"

New-Item -ItemType Directory -Force -Path $serverDir, $clientDir | Out-Null
# Remove stale files so keytool never prompts about an existing alias.
Remove-Item -Force -ErrorAction SilentlyContinue $p12, $crt

$san = "SAN=dns:localhost,ip:127.0.0.1"
if ($AddIp) { $san += ",ip:$AddIp" }

$genArgs = @(
    "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
    "-keystore", $p12, "-storetype", "PKCS12", "-storepass", $pw, "-keypass", $pw,
    "-dname", "CN=localhost, OU=Lab, O=SecureChat, L=Hanoi, C=VN", "-ext", $san
)
if ($StartDate) { $genArgs += @("-startdate", $StartDate) }

& keytool @genArgs
if ($LASTEXITCODE -ne 0) { throw "keytool -genkeypair failed ($LASTEXITCODE)" }

& keytool -exportcert -rfc -alias server -keystore $p12 -storetype PKCS12 -storepass $pw -file $crt
if ($LASTEXITCODE -ne 0) { throw "keytool -exportcert failed ($LASTEXITCODE)" }

Write-Host "Generated $p12 and $crt"
# Verification step: show owner, validity and SAN entries.
& keytool -printcert -file $crt
if ($LASTEXITCODE -ne 0) { throw "keytool -printcert failed ($LASTEXITCODE)" }
