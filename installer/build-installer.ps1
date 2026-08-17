# Builds PocketPad-Setup.exe: self-contained publish (no .NET needed on the
# target PC) + Inno Setup compile. Run from anywhere.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$payload = Join-Path $PSScriptRoot "payload"

# 1. self-contained publish (multi-file: single-file + compression breaks
#    WPF's native DLL loading — DllNotFoundException at first window)
if (Test-Path $payload) { Remove-Item $payload -Recurse -Force }
dotnet publish (Join-Path $root "windows\PocketPad.App") -c Release -r win-x64 `
  --self-contained true -o $payload -v quiet
if ($LASTEXITCODE -ne 0) { throw "publish failed" }
Get-ChildItem $payload -Filter "*.pdb" | Remove-Item -Force
if (Test-Path (Join-Path $payload "PocketPad.App.exe")) {
  Move-Item (Join-Path $payload "PocketPad.App.exe") (Join-Path $payload "PocketPad for PC.exe") -Force
}

# 2. driver redistributable must be present (downloaded once; not in git).
#    It is compiled into PocketPad-Setup.exe and then executed with admin
#    rights on every user's machine, so verify it on every build rather than
#    only after downloading: the bare Test-Path guard meant a truncated
#    download, a proxy error page, or a tampered artifact would be cached once
#    and silently reused by every later build.
$driverUrl    = "https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_arm64.exe"
$driverSha256 = "89220A7865076B342892F98865F3499FB7C4CFD673159E89D352C360FD014C6A"
$driver = Join-Path $PSScriptRoot "redist\ViGEmBus_Setup.exe"

if (-not (Test-Path $driver)) {
  New-Item -ItemType Directory -Force -Path (Split-Path $driver) | Out-Null
  Invoke-WebRequest -Uri $driverUrl -OutFile $driver -UseBasicParsing
}

$actualSha = (Get-FileHash $driver -Algorithm SHA256).Hash
if ($actualSha -ne $driverSha256) {
  Remove-Item $driver -Force
  throw "ViGEmBus redist SHA256 mismatch (expected $driverSha256, got $actualSha). The bad copy was deleted; re-run to download it again."
}

$sig = Get-AuthenticodeSignature $driver
if ($sig.Status -ne "Valid" -or $sig.SignerCertificate.Subject -notlike "*Nefarius Software Solutions*") {
  throw "ViGEmBus redist is not validly signed by Nefarius (status=$($sig.Status), signer=$($sig.SignerCertificate.Subject))"
}

# 3. compile the installer
$iscc = @(
  "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
  "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
  "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw "Inno Setup not found" }

# PocketPad.iss uses ArchitecturesInstallIn64BitMode=x64compatible, which only
# exists from Inno 6.3 (it was 'x64' before). On 6.0-6.2 ISCC aborts with an
# opaque "[Setup] section directive is invalid".
# Feature-probe rather than version-check: ISCC.exe ships no usable version
# resource (it reports 0.0.0.0) and its banner says only "Inno Setup 6", so
# there is no minor version to read. Compiling a throwaway script that uses the
# directive answers the actual question, and works for portable installs too.
$probeDir = Join-Path ([IO.Path]::GetTempPath()) "pocketpad-iscc-probe"
if (Test-Path $probeDir) { Remove-Item $probeDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
$probeIss = Join-Path $probeDir "probe.iss"
@"
[Setup]
AppName=probe
AppVersion=1
DefaultDirName={localappdata}\probe
OutputDir=$probeDir
OutputBaseFilename=probe
ArchitecturesInstallIn64BitMode=x64compatible
"@ | Set-Content -LiteralPath $probeIss -Encoding ascii

$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $iscc $probeIss /Q | Out-Null
$probeOk = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $prevEap
Remove-Item $probeDir -Recurse -Force -ErrorAction SilentlyContinue

if (-not $probeOk) {
  throw "Inno Setup at $iscc rejects ArchitecturesInstallIn64BitMode=x64compatible - PocketPad.iss needs Inno Setup 6.3 or newer."
}

& $iscc (Join-Path $PSScriptRoot "PocketPad.iss") /Q
if ($LASTEXITCODE -ne 0) { throw "ISCC failed" }
$out = Join-Path $PSScriptRoot "output\PocketPad-Setup.exe"
"built: {0} ({1:N1} MB)" -f $out, ((Get-Item $out).Length/1MB)
