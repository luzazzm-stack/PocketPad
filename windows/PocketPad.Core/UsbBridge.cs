using System.Diagnostics;

namespace PocketPad.Core;

/// <summary>
/// Makes every phone plugged in by USB reach this PC through the cable.
///
/// Every couple of seconds it lists adb devices and, for each phone it has not set up
/// yet, runs <c>adb reverse</c> for the control and stream ports. The phone app then
/// finds the PC at 127.0.0.1 and connects by itself. Needs USB debugging on the phone;
/// without adb it simply does nothing, and Wi-Fi keeps working as before.
/// </summary>
public sealed class UsbBridge
{
    private readonly HashSet<string> _ready = new();

    /// <summary>A phone was set up for USB: (adb serial, model).</summary>
    public event Action<string, string>? PhoneReady;

    /// <summary>adb.exe: bundled next to the app, else the Android SDK, else PATH. Null if none.</summary>
    public static string? FindAdb()
    {
        var here = Path.Combine(AppContext.BaseDirectory, "adb", "adb.exe");
        if (File.Exists(here)) return here;
        var sdk = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Android", "Sdk", "platform-tools", "adb.exe");
        if (File.Exists(sdk)) return sdk;
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
        {
            try
            {
                var p = Path.Combine(dir.Trim('"'), "adb.exe");
                if (File.Exists(p)) return p;
            }
            catch (ArgumentException) { /* malformed PATH entry */ }
        }
        return null;
    }

    public async Task RunAsync(CancellationToken ct)
    {
        var adb = FindAdb();
        if (adb is null)
            return;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var devices = await Run(adb, "devices -l", ct).ConfigureAwait(false);
                var seen = new HashSet<string>();
                foreach (var line in devices.Split('\n'))
                {
                    var parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                    if (parts.Length < 2 || parts[1] != "device")
                        continue;                  // header, "unauthorized", "offline"
                    var serial = parts[0];
                    seen.Add(serial);
                    if (_ready.Contains(serial))
                        continue;
                    var ok = await Run(adb, $"-s {serial} reverse tcp:{ControlServer.DefaultPort} tcp:{ControlServer.DefaultPort}", ct)
                        .ConfigureAwait(false);
                    await Run(adb, $"-s {serial} reverse tcp:{StreamStateListener.DefaultPort} tcp:{StreamStateListener.DefaultPort}", ct)
                        .ConfigureAwait(false);
                    if (ok.Contains("error", StringComparison.OrdinalIgnoreCase))
                        continue;                  // try again next round
                    _ready.Add(serial);
                    var model = parts.FirstOrDefault(p => p.StartsWith("model:"))?[6..] ?? serial;
                    PhoneReady?.Invoke(serial, model.Replace('_', ' '));
                }
                _ready.IntersectWith(seen);        // unplugged phones get set up again next time
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception)
            {
                // adb hiccup - keep polling
            }
            try { await Task.Delay(2000, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { break; }
        }
    }

    private static async Task<string> Run(string adb, string args, CancellationToken ct)
    {
        var psi = new ProcessStartInfo(adb, args)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        using var p = Process.Start(psi)!;
        var outTask = p.StandardOutput.ReadToEndAsync(ct);
        var errTask = p.StandardError.ReadToEndAsync(ct);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(15));
        try
        {
            await p.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            try { p.Kill(); } catch (InvalidOperationException) { }
            return "error: timeout";
        }
        return await outTask.ConfigureAwait(false) + await errTask.ConfigureAwait(false);
    }
}
