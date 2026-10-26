# Registers (or removes) the "PocketPad" sign-in task: starts PocketPad for PC in the tray
# with admin rights and no UAC prompt, so phones (Wi-Fi or USB) can connect right after a
# restart. Battery limits are switched off - Windows' default would skip it on a laptop.
#   autostart.ps1 -Exe "C:\Program Files\PocketPad\PocketPad for PC.exe"   (install)
#   autostart.ps1 -Remove                                                  (uninstall)
param([string]$Exe, [switch]$Remove)
$name = "PocketPad"
if ($Remove) {
    Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
    exit 0
}
$user = (Get-CimInstance Win32_ComputerSystem).UserName   # the person signed in, not the elevating admin
if (-not $user) { $user = "$env:USERDOMAIN\$env:USERNAME" }
$action    = New-ScheduledTaskAction -Execute $Exe -Argument "--tray"
$trigger   = New-ScheduledTaskTrigger -AtLogOn -User $user
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                 -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -StartWhenAvailable
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Principal $principal `
    -Settings $settings -Description "Starts PocketPad for PC in the tray at sign-in." -Force | Out-Null
