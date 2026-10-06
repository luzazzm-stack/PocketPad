; PocketPad for PC — one-click installer
; Build with installer\build-installer.ps1 (publishes the app, then compiles this).

#define AppName "PocketPad for PC"
#define AppVersion "0.6.3"
#define AppExe "PocketPad for PC.exe"

[Setup]
AppId={{8B71D4C2-6E1A-4F0B-9C64-PocketPadPC1}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=PocketPad
DefaultDirName={autopf}\PocketPad
DefaultGroupName=PocketPad
UninstallDisplayIcon={app}\{#AppExe}
OutputDir=output
OutputBaseFilename=PocketPad-Setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; as close to one-click as it gets: no directory page, no "ready?" page —
; run it, click Install, done
DisableProgramGroupPage=yes
DisableDirPage=yes
DisableReadyPage=yes
DisableWelcomePage=no
; driver + firewall need admin
PrivilegesRequired=admin
; 'x64compatible' needs Inno Setup 6.3 or newer — before that the identifier
; was 'x64', and 6.0-6.2 rejects this with an opaque "[Setup] section directive
; is invalid". build-installer.ps1 enforces the compiler version.
ArchitecturesInstallIn64BitMode=x64compatible
; The app hides to the tray on close, so "already running" is the normal state
; during an upgrade. Without this Inno cannot replace the exe or its runtime
; DLLs, and either schedules a reboot-time replace or leaves the old binary in
; place while reporting success. Must match MutexName in App.xaml.cs.
AppMutex=PocketPadForPC_SingleInstance

[Files]
Source: "payload\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs
Source: "redist\ViGEmBus_Setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Icons]
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\PocketPad Manual"; Filename: "{app}\manual.html"

[Run]
; Controller driver. The redist is an Advanced Installer bootstrapper:
; /exenoui suppresses its own wizard, /qn the MSI UI, /norestart stops it
; rebooting the machine mid-install. Unswitched it opened a second, separate
; wizard — sometimes behind the setup window — while our progress bar sat on
; "Installing the controller driver", and a user who cancelled that got a
; silently driver-less install.
; Run it unconditionally and let the bundle decide: it detects an up-to-date
; driver and skips itself. The old RegKeyExists probe treated a leftover
; service key, or a driver older than the bundled one, as "already installed".
Filename: "{tmp}\ViGEmBus_Setup.exe"; \
  Parameters: "/exenoui /qn /norestart"; \
  StatusMsg: "Checking the controller driver..."; \
  Flags: runhidden waituntilterminated
; Let the phone reach the app through Windows Firewall. Delete before adding:
; netsh does not deduplicate, so without this every upgrade leaves another
; identical inbound rule behind. Deleting a rule that isn't there exits
; non-zero, which Inno ignores for [Run] entries.
Filename: "{sys}\netsh.exe"; \
  Parameters: "advfirewall firewall delete rule name=""PocketPad for PC"""; \
  StatusMsg: "Allowing PocketPad through the firewall..."; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; \
  Parameters: "advfirewall firewall add rule name=""PocketPad for PC"" dir=in action=allow program=""{app}\{#AppExe}"" enable=yes"; \
  StatusMsg: "Allowing PocketPad through the firewall..."; Flags: runhidden waituntilterminated
; runasoriginaluser matters even though the app now requests
; requireAdministrator. PrivilegesRequired=admin means a standard user running
; setup is prompted for an ADMINISTRATOR's credentials, and setup then runs as
; that account - not the person at the keyboard. Without this flag the app
; inherits it, so its tray icon, clipboard and window belong to the wrong user,
; and the named Mutex it creates carries that account's DACL. Launching as the
; original user costs one UAC prompt and matches what the README and manual
; already tell people to expect on every launch.
Filename: "{app}\{#AppExe}"; Description: "Start {#AppName} now"; \
  Flags: postinstall nowait skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\netsh.exe"; \
  Parameters: "advfirewall firewall delete rule name=""PocketPad for PC"""; \
  Flags: runhidden waituntilterminated; RunOnceId: "DelFwRule"
