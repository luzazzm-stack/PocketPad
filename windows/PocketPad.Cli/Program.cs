using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using PocketPad.Cli;
using PocketPad.Core;

// PocketPad Link — Phase 1 console host.
// Listens for phone input (TCP 46822 handshake + UDP 46821 state) and drives
// a ViGEm virtual Xbox 360 pad. Games see a real controller.

Console.Title = "PocketPad Link";
Console.WriteLine();
Console.WriteLine("  P O C K E T P A D   L I N K              v0.1");
Console.WriteLine("  ============================================");

// ---- virtual pad ----
ViGEmClient vigem;
try
{
    vigem = new ViGEmClient();
}
catch (Exception e)
{
    Console.WriteLine();
    Console.WriteLine("  Could not start the virtual controller.");
    Console.WriteLine("  The ViGEm driver is missing. Install it from:");
    Console.WriteLine("      https://github.com/nefarius/ViGEmBus/releases");
    Console.WriteLine($"  (details: {e.Message})");
    Console.WriteLine();
    Console.WriteLine("  Press any key to close.");
    if (!Console.IsInputRedirected) Console.ReadKey(true);
    return 1;
}

using var _vigem = vigem;
IXbox360Controller pad = vigem.CreateXbox360Controller();
pad.Connect();
Console.WriteLine("  Controller ready.");

// ---- pairing info ----
string token = RandomNumberGenerator.GetHexString(8, lowercase: true);
var ips = LocalIPv4s().ToList();
Console.WriteLine();
Console.WriteLine("  Open PocketPad on your phone and enter:");
Console.WriteLine();
Console.ForegroundColor = ConsoleColor.Cyan;
foreach (var ip in ips)
    Console.WriteLine($"      PC address   {ip}");
Console.WriteLine($"      Code         {token}");
Console.ResetColor();
Console.WriteLine();
if (ips.Count > 1)
    Console.WriteLine("  (More than one address listed? Try the first one.)");
Console.WriteLine("  The phone must be on the same Wi-Fi as this PC.");
Console.WriteLine();

// ---- servers ----
using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };

using var control = new ControlServer(token);
using var udp = new UdpStateListener();

var mouse = new MouseInjector();
long packets = 0;
control.ClientConnected += (name, ep) =>
{
    udp.ResetSequence();
    Console.ForegroundColor = ConsoleColor.Green;
    Console.WriteLine($"  CONNECTED   {name}  ({ep.Address})");
    Console.ResetColor();
    Console.WriteLine("  You can start your game now.");
};
control.ClientDisconnected += () =>
{
    ResetPad(pad);
    mouse.ReleaseAll();
    Console.ForegroundColor = ConsoleColor.Yellow;
    Console.WriteLine($"  Disconnected. ({Interlocked.Read(ref packets):N0} inputs sent)");
    Console.ResetColor();
    Console.WriteLine("  Waiting for the phone again...");
    Interlocked.Exchange(ref packets, 0);
};
udp.StateReceived += (state, _) =>
{
    ApplyState(pad, state);
    Interlocked.Increment(ref packets);
};
udp.MouseReceived += (m, _) =>
{
    mouse.Apply(m);
    Interlocked.Increment(ref packets);
};

Console.WriteLine("  Waiting for the phone...        (close this window to stop)");
try
{
    await Task.WhenAll(control.RunAsync(cts.Token), udp.RunAsync(cts.Token));
}
catch (OperationCanceledException)
{
    // normal shutdown
}

pad.Disconnect();
Console.WriteLine("Bye.");
return 0;

// ---- helpers ----

static void ApplyState(IXbox360Controller pad, StatePacket s)
{
    var b = s.Buttons;
    pad.SetButtonState(Xbox360Button.A, b.HasFlag(PadButtons.A));
    pad.SetButtonState(Xbox360Button.B, b.HasFlag(PadButtons.B));
    pad.SetButtonState(Xbox360Button.X, b.HasFlag(PadButtons.X));
    pad.SetButtonState(Xbox360Button.Y, b.HasFlag(PadButtons.Y));
    pad.SetButtonState(Xbox360Button.LeftShoulder, b.HasFlag(PadButtons.LB));
    pad.SetButtonState(Xbox360Button.RightShoulder, b.HasFlag(PadButtons.RB));
    pad.SetButtonState(Xbox360Button.Back, b.HasFlag(PadButtons.Back));
    pad.SetButtonState(Xbox360Button.Start, b.HasFlag(PadButtons.Start));
    pad.SetButtonState(Xbox360Button.LeftThumb, b.HasFlag(PadButtons.L3));
    pad.SetButtonState(Xbox360Button.RightThumb, b.HasFlag(PadButtons.R3));

    // v1: digital triggers
    pad.SetSliderValue(Xbox360Slider.LeftTrigger, b.HasFlag(PadButtons.LT) ? (byte)255 : (byte)0);
    pad.SetSliderValue(Xbox360Slider.RightTrigger, b.HasFlag(PadButtons.RT) ? (byte)255 : (byte)0);

    (bool up, bool right, bool down, bool left) = s.Dpad switch
    {
        Dpad.Up        => (true, false, false, false),
        Dpad.UpRight   => (true, true, false, false),
        Dpad.Right     => (false, true, false, false),
        Dpad.DownRight => (false, true, true, false),
        Dpad.Down      => (false, false, true, false),
        Dpad.DownLeft  => (false, false, true, true),
        Dpad.Left      => (false, false, false, true),
        Dpad.UpLeft    => (true, false, false, true),
        _              => (false, false, false, false),
    };
    pad.SetButtonState(Xbox360Button.Up, up);
    pad.SetButtonState(Xbox360Button.Right, right);
    pad.SetButtonState(Xbox360Button.Down, down);
    pad.SetButtonState(Xbox360Button.Left, left);

    pad.SetAxisValue(Xbox360Axis.LeftThumbX, s.Lx);
    pad.SetAxisValue(Xbox360Axis.LeftThumbY, s.Ly);
    pad.SetAxisValue(Xbox360Axis.RightThumbX, s.Rx);
    pad.SetAxisValue(Xbox360Axis.RightThumbY, s.Ry);

    pad.SubmitReport();
}

static void ResetPad(IXbox360Controller pad)
{
    ApplyState(pad, new StatePacket(0, PadButtons.None, Dpad.Neutral, 0, 0, 0, 0));
}

static IEnumerable<IPAddress> LocalIPv4s()
{
    var host = Dns.GetHostEntry(Dns.GetHostName());
    foreach (var ip in host.AddressList)
        if (ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
            yield return ip;
}
