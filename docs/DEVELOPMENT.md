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
# build the installer (self-contained; downloads the driver bundle if needed)
powershell -File installer\build-installer.ps1

# upload both apps to the existing release (or create a new tag)
gh release upload v0.3 installer\output\PocketPad-Setup.exe --clobber
Copy-Item android\app\build\outputs\apk\debug\app-debug.apk $env:TEMP\PocketPad.apk
gh release upload v0.3 $env:TEMP\PocketPad.apk --clobber
```

Updating machines that already have PocketPad: run the new Setup.exe — it
upgrades in place (same AppId). Phones: install the new APK over the old one.

## ⚠ Gotchas learned the hard way

- **Debug APK signatures differ per machine.** Android signs debug builds with
  a per-machine key, so an APK built on laptop B won't install *over* one from
  laptop A — uninstall PocketPad from the phone first (or copy
  `%USERPROFILE%\.android\debug.keystore` from the first machine). A proper
  release keystore fixes this for good at launch.
- **Don't publish the WPF app single-file + compressed** — it crashes with
  DllNotFoundException. `build-installer.ps1` already does it right.
- **`sdkmanager` is deprecated and silently does nothing** — use
  `android.exe sdk install` as shown above.
- **Protocol changes**: `protocol/PROTOCOL.md` is the single source of truth;
  the golden-byte tests in both apps must be updated together.
- The manual (`docs/manual.html`) is bundled into BOTH apps automatically at
  build time — edit it in docs/ only.
