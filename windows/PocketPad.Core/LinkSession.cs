using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace PocketPad.Core;

/// <summary>
/// The whole PC side in one object: virtual Xbox pad, control server, UDP
/// listener, mouse injection. UIs (WPF app, dev CLI) subscribe to events and
/// render — they never touch sockets or ViGEm directly.
///
/// Throws <see cref="DriverMissingException"/> from the constructor when the
/// ViGEm bus driver is not installed.
/// </summary>
public sealed class LinkSession : IDisposable
{
    public sealed class DriverMissingException(Exception inner)
        : Exception("The ViGEm bus driver is not installed.", inner);

    private readonly ViGEmClient _vigem;
    private readonly IXbox360Controller _pad;
    private readonly ControlServer _control;
    private readonly UdpStateListener _udp;
    private readonly MouseInjector _mouse = new();
    private readonly CancellationTokenSource _cts = new();
    private long _packets;

    /// <summary>Pairing token, regenerated per session (per app launch).</summary>
    public string Token { get; }

    /// <summary>Phone connected: device name + remote address.</summary>
    public event Action<string, IPEndPoint>? PhoneConnected;

    /// <summary>Phone gone; argument is packets received this session.</summary>
    public event Action<long>? PhoneDisconnected;

    /// <summary>Phone-measured round-trip in ms (the "lat" control message).</summary>
    public event Action<int>? LatencyReported;

    public long PacketsReceived => Interlocked.Read(ref _packets);

    public LinkSession()
    {
        try
        {
            _vigem = new ViGEmClient();
        }
        catch (Exception e)
        {
            throw new DriverMissingException(e);
        }

        _pad = _vigem.CreateXbox360Controller();
        _pad.Connect();

        Token = RandomNumberGenerator.GetHexString(8, lowercase: true);
        _control = new ControlServer(Token);
        _udp = new UdpStateListener();

        _control.ClientConnected += (name, ep) =>
        {
            _udp.ResetSequence();
            PhoneConnected?.Invoke(name, ep);
        };
        _control.ClientDisconnected += () =>
        {
            ResetPad();
            _mouse.ReleaseAll();
            var count = Interlocked.Exchange(ref _packets, 0);
            PhoneDisconnected?.Invoke(count);
        };
        _control.LatencyReported += ms => LatencyReported?.Invoke(ms);

        _udp.StateReceived += (state, _) => { ApplyState(state); Interlocked.Increment(ref _packets); };
        _udp.MouseReceived += (m, _) => { _mouse.Apply(m); Interlocked.Increment(ref _packets); };
    }

    /// <summary>Start both servers. Returns the running task (faults on fatal errors).</summary>
    public Task RunAsync() =>
        Task.WhenAll(_control.RunAsync(_cts.Token), _udp.RunAsync(_cts.Token));

    /// <summary>Kick the current phone off (the UI's Disconnect button).</summary>
    public void DisconnectPhone() => _control.DisconnectClient();

    /// <summary>
    /// The machine's primary LAN IPv4 — the address a phone on the same network
    /// should dial. Falls back to the first non-loopback IPv4.
    /// </summary>
    public static IPAddress PrimaryIPv4()
    {
        try
        {
            // Routing trick: no packet is sent, but the OS picks the outbound
            // interface, whose address is the one to show the user.
            using var probe = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            probe.Connect("8.8.8.8", 65530);
            if (probe.LocalEndPoint is IPEndPoint ep && !IPAddress.IsLoopback(ep.Address))
                return ep.Address;
        }
        catch (SocketException) { /* offline — fall through */ }

        var host = Dns.GetHostEntry(Dns.GetHostName());
        foreach (var ip in host.AddressList)
            if (ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
                return ip;
        return IPAddress.Loopback;
    }

    /// <summary>QR payload the phone app understands (PROTOCOL.md pairing).</summary>
    public string PairingUri(IPAddress host) =>
        $"pocketpad://pair?host={host}&tcp={ControlServer.DefaultPort}&token={Token}";

    private void ApplyState(StatePacket s)
    {
        var b = s.Buttons;
        _pad.SetButtonState(Xbox360Button.A, b.HasFlag(PadButtons.A));
        _pad.SetButtonState(Xbox360Button.B, b.HasFlag(PadButtons.B));
        _pad.SetButtonState(Xbox360Button.X, b.HasFlag(PadButtons.X));
        _pad.SetButtonState(Xbox360Button.Y, b.HasFlag(PadButtons.Y));
        _pad.SetButtonState(Xbox360Button.LeftShoulder, b.HasFlag(PadButtons.LB));
        _pad.SetButtonState(Xbox360Button.RightShoulder, b.HasFlag(PadButtons.RB));
        _pad.SetButtonState(Xbox360Button.Back, b.HasFlag(PadButtons.Back));
        _pad.SetButtonState(Xbox360Button.Start, b.HasFlag(PadButtons.Start));
        _pad.SetButtonState(Xbox360Button.LeftThumb, b.HasFlag(PadButtons.L3));
        _pad.SetButtonState(Xbox360Button.RightThumb, b.HasFlag(PadButtons.R3));

        _pad.SetSliderValue(Xbox360Slider.LeftTrigger, b.HasFlag(PadButtons.LT) ? (byte)255 : (byte)0);
        _pad.SetSliderValue(Xbox360Slider.RightTrigger, b.HasFlag(PadButtons.RT) ? (byte)255 : (byte)0);

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
        _pad.SetButtonState(Xbox360Button.Up, up);
        _pad.SetButtonState(Xbox360Button.Right, right);
        _pad.SetButtonState(Xbox360Button.Down, down);
        _pad.SetButtonState(Xbox360Button.Left, left);

        _pad.SetAxisValue(Xbox360Axis.LeftThumbX, s.Lx);
        _pad.SetAxisValue(Xbox360Axis.LeftThumbY, s.Ly);
        _pad.SetAxisValue(Xbox360Axis.RightThumbX, s.Rx);
        _pad.SetAxisValue(Xbox360Axis.RightThumbY, s.Ry);

        _pad.SubmitReport();
    }

    private void ResetPad() =>
        ApplyState(new StatePacket(0, PadButtons.None, Dpad.Neutral, 0, 0, 0, 0));

    public void Dispose()
    {
        _cts.Cancel();
        try { _pad.Disconnect(); } catch (InvalidOperationException) { /* never connected */ }
        _control.Dispose();
        _udp.Dispose();
        _vigem.Dispose();
        _cts.Dispose();
    }
}
