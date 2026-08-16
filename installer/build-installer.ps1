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

# 2. driver redistributable must be present (downloaded once; not in git)
$driver = Join-Path $PSScriptRoot "redist\ViGEmBus_Setup.exe"
if (-not (Test-Path $driver)) {
  New-Item -ItemType Directory -Force -Path (Split-Path $driver) | Out-Null
  Invoke-WebRequest -Uri "https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_arm64.exe" `
    -OutFile $driver -UseBasicParsing
}

# 3. compile the installer
$iscc = @(
  "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
  "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
  "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw "Inno Setup not found" }
& $iscc (Join-Path $PSScriptRoot "PocketPad.iss") /Q
if ($LASTEXITCODE -ne 0) { throw "ISCC failed" }
$out = Join-Path $PSScriptRoot "output\PocketPad-Setup.exe"
"built: {0} ({1:N1} MB)" -f $out, ((Get-Item $out).Length/1MB)
