; PocketPad for PC — one-click installer
; Build with installer\build-installer.ps1 (publishes the app, then compiles this).

#define AppName "PocketPad for PC"
#define AppVersion "0.3"
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
DisableProgramGroupPage=yes
; driver + firewall need admin
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible

[Files]
Source: "payload\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs
Source: "redist\ViGEmBus_Setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Icons]
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\PocketPad Manual"; Filename: "{app}\manual.html"

[Run]
; Controller driver — only when it isn't installed yet.
Filename: "{tmp}\ViGEmBus_Setup.exe"; \
  StatusMsg: "Installing the controller driver (one time)..."; \
  Check: not ViGEmInstalled; Flags: waituntilterminated
; Let the phone reach the app through Windows Firewall.
Filename: "{sys}\netsh.exe"; \
  Parameters: "advfirewall firewall add rule name=""PocketPad for PC"" dir=in action=allow program=""{app}\{#AppExe}"" enable=yes"; \
  StatusMsg: "Allowing PocketPad through the firewall..."; Flags: runhidden waituntilterminated
Filename: "{app}\{#AppExe}"; Description: "Start {#AppName} now"; \
  Flags: postinstall nowait skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; \
  Parameters: "advfirewall firewall delete rule name=""PocketPad for PC"""; \
  Flags: runhidden waituntilterminated; RunOnceId: "DelFwRule"

[Code]
function ViGEmInstalled: Boolean;
begin
  Result := RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\ViGEmBus');
end;
