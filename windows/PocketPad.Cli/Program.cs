using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using PocketPad.Core;

// PocketPad Link — Phase 1 console host.
// Listens for phone input (TCP 46822 handshake + UDP 46821 state) and drives
// a ViGEm virtual Xbox 360 pad. Games see a real controller.

Console.WriteLine("PocketPad Link v0.1");
Console.WriteLine("-------------------");

// ---- virtual pad ----
ViGEmClient vigem;
try
{
    vigem = new ViGEmClient();
}
catch (Exception e)
{
    Console.Error.WriteLine("Could not reach the ViGEm bus driver. Is ViGEmBus installed?");
    Console.Error.WriteLine($"  ({e.Message})");
    return 1;
}

using var _vigem = vigem;
IXbox360Controller pad = vigem.CreateXbox360Controller();
pad.Connect();
Console.WriteLine("Virtual Xbox 360 pad created (check joy.cpl).");

// ---- pairing info ----
string token = RandomNumberGenerator.GetHexString(8, lowercase: true);
Console.WriteLine();
Console.WriteLine("Connect from the phone with:");
foreach (var ip in LocalIPv4s())
    Console.WriteLine($"  host={ip}  tcp={ControlServer.DefaultPort}  token={token}");
Console.WriteLine();

// ---- servers ----
using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };

using var control = new ControlServer(token);
using var udp = new UdpStateListener();

long packets = 0;
control.ClientConnected += (name, ep) =>
{
    udp.ResetSequence();
    Console.WriteLine($"[+] {name} connected from {ep.Address}");
};
control.ClientDisconnected += () =>
{
    ResetPad(pad);
    Console.WriteLine($"[-] phone disconnected ({Interlocked.Read(ref packets)} packets this session)");
    Interlocked.Exchange(ref packets, 0);
};
udp.StateReceived += (state, _) =>
{
    ApplyState(pad, state);
    Interlocked.Increment(ref packets);
};

Console.WriteLine("Waiting for the phone... (Ctrl+C to quit)");
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
