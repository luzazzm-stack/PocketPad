# Developing PocketPad on a new machine

Everything needed to clone, build, and ship from a fresh Windows laptop.
(If you only want to *use* PocketPad, ignore this file — grab
`PocketPad-Setup.exe` from the Releases page instead.)

## 1 · One-time machine setup

Install the toolchain (PowerShell, one line at a time):

```powershell
winget install --id GitHub.cli -e
winget install --id Git.Git -e
winget install --id EclipseAdoptium.Temurin.17.JDK -e
winget install --id Microsoft.DotNet.SDK.8 -e
winget install --id JRSoftware.InnoSetup -e
winget install --id ViGEm.ViGEmBus -e          # controller driver (to run/test locally)
```

Android SDK (no Android Studio needed):

```powershell
# download "commandline tools" from https://developer.android.com/studio
# unzip so sdkmanager sits at %LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin
# then:
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.<version>-hotspot"
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\android.exe" --no-metrics sdk install "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

Set user environment variables: `JAVA_HOME` (the JDK path above) and
`ANDROID_HOME` = `%LOCALAPPDATA%\Android\Sdk`.

## 2 · Get the code

```powershell
gh auth login --web          # log in to the GitHub account that owns the repo
gh repo clone luzazzm-stack/PocketPad
cd PocketPad
```

Create `android\local.properties` containing (adjust the user name):

```
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

## 3 · Build & test

```powershell
# Windows side
cd windows
dotnet build          # everything
dotnet test           # protocol tests must pass

# Android side
cd ..\android
.\gradlew.bat assembleDebug testDebugUnitTest
# install to a phone with USB debugging enabled:
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Dev helpers in `windows\`: `PocketPad.Cli` (console server that prints the
pairing code), `PocketPad.XInputProbe <slot> <seconds>` (shows what a game
sees), `PocketPad.FakePhone <host> <token> <name> <A|B|X|Y>` (a protocol-
speaking fake phone — multiplayer testing without hardware).

## 4 · Ship an update

```powershell
# build the installer (self-contained; downloads and verifies the driver bundle)
powershell -File installer\build-installer.ps1

# build the signed release APK
cd android; .\gradlew.bat assembleRelease test; cd ..

# cut the tag and attach both apps (swap v0.6 for the version you are shipping)
gh release create v0.6 --title "PocketPad v0.6" --notes "..."
gh release upload v0.6 installer\output\PocketPad-Setup.exe --clobber
Copy-Item android\app\build\outputs\apk\release\app-release.apk $env:TEMP\PocketPad.apk
gh release upload v0.6 $env:TEMP\PocketPad.apk --clobber
```

Keep three version numbers in step when shipping: `versionCode`/`versionName`
in `android\app\build.gradle.kts`, `AppVersion` in `installer\PocketPad.iss`,
and the tag.

Updating machines that already have PocketPad: run the new Setup.exe — it
upgrades in place (same AppId). Phones: install the new APK over the old one.

## ⚠ Gotchas learned the hard way

- **Ship the release APK, not the debug one.** `keystore\pocketpad.jks` is
  checked in and both build types sign with it, so any build from any machine
  installs *over* the previous one and keeps the player's layout. That is the
  whole reason the key is in the repo. Building with a throwaway debug key
  again would force an uninstall, wiping their settings.
- **The PC app requests administrator** (`windows\PocketPad.App\app.manifest`).
  Mouse mode injects input with `SendInput`, which UIPI blocks from a
  medium-integrity process whenever an elevated window has focus — minimise
  PocketPad next to an admin window and the pointer dies. `uiAccess="true"`
  would fix it without full admin, but needs an Authenticode-signed exe.
- **Don't publish the WPF app single-file + compressed** — it crashes with
  DllNotFoundException. `build-installer.ps1` already does it right.
- **`sdkmanager` is deprecated and silently does nothing** — use
  `android.exe sdk install` as shown above.
- **Protocol changes**: `protocol/PROTOCOL.md` is the single source of truth;
  the golden-byte tests in both apps must be updated together.
- The manual (`docs/manual.html`) is bundled into BOTH apps automatically at
  build time — edit it in docs/ only.
