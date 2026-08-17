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
    private readonly IXbox360Controller?[] _pads = new IXbox360Controller?[StatePacket.MaxPlayers];
    private readonly object _padLock = new();
    private readonly ControlServer _control;
    private readonly UdpStateListener _udp;
    private readonly MouseInjector _mouse = new();
    private readonly CancellationTokenSource _cts = new();
    private long _packets;
    private int _phoneCount;

    /// <summary>Pairing token, regenerated per session (per app launch).</summary>
    public string Token { get; }

    /// <summary>Phone connected: (player, device name, remote address).</summary>
    public event Action<int, string, IPEndPoint>? PhoneConnected;

    /// <summary>A phone left: (player, phones still connected).</summary>
    public event Action<int, int>? PhoneDisconnected;

    /// <summary>Phone-measured round-trip: (player, ms).</summary>
    public event Action<int, int>? LatencyReported;

    public long PacketsReceived => Interlocked.Read(ref _packets);

    public int PhoneCount => Volatile.Read(ref _phoneCount);

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

        Token = RandomNumberGenerator.GetHexString(8, lowercase: true);

        // Both listeners bind in their constructors, so either can throw
        // SocketException on a port clash. A faulting constructor hands the
        // caller no reference, so nothing downstream can ever release the
        // driver handle or the connected pad — unwind them here instead of
        // leaving a phantom controller attached until the process exits.
        IXbox360Controller? pad0 = null;
        ControlServer? control = null;
        UdpStateListener? udp = null;
        try
        {
            // Player 0's pad stays alive for the whole session so a game launched
            // before the phone connects still finds a controller. Pads 1-3 are
            // created when their phone joins and released when it leaves.
            pad0 = _vigem.CreateXbox360Controller();
            pad0.Connect();

            control = new ControlServer(Token);
            udp = new UdpStateListener();
        }
        catch
        {
            udp?.Dispose();
            control?.Dispose();
            try { pad0?.Disconnect(); } catch (InvalidOperationException) { /* never connected */ }
            _vigem.Dispose();
            throw;
        }

        _pads[0] = pad0;
        _control = control;
        _udp = udp;

        _control.ClientConnected += (player, name, ep) =>
        {
            lock (_padLock)
            {
                if (_pads[player] is null)
                {
                    _pads[player] = _vigem.CreateXbox360Controller();
                    _pads[player]!.Connect();
                }
            }
            _udp.ResetSequence(player);
            Interlocked.Increment(ref _phoneCount);
            PhoneConnected?.Invoke(player, name, ep);
        };
        _control.ClientDisconnected += player =>
        {
            lock (_padLock)
            {
                if (_pads[player] is { } pad)
                {
                    ApplyState(pad, new StatePacket(0, PadButtons.None, Dpad.Neutral, 0, 0, 0, 0));
                    if (player != 0) // player 0's pad survives for relaunch-free reconnects
                    {
                        pad.Disconnect();
                        _pads[player] = null;
                    }
                }
            }
            _mouse.ReleaseAll();
            var remaining = Interlocked.Decrement(ref _phoneCount);
            PhoneDisconnected?.Invoke(player, remaining);
        };
        _control.LatencyReported += (player, ms) => LatencyReported?.Invoke(player, ms);

        _udp.StateReceived += (state, _) =>
        {
            lock (_padLock)
            {
                if (_pads[state.Player] is { } pad) ApplyState(pad, state);
            }
            Interlocked.Increment(ref _packets);
        };
        _udp.MouseReceived += (m, _) => { _mouse.Apply(m); Interlocked.Increment(ref _packets); };
    }

    /// <summary>Start both servers. Returns the running task (faults on fatal errors).</summary>
    public Task RunAsync() =>
        Task.WhenAll(_control.RunAsync(_cts.Token), _udp.RunAsync(_cts.Token));

    /// <summary>Kick every connected phone off (the UI's Disconnect button).</summary>
    public void DisconnectPhones() => _control.DisconnectAll();

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

    private static void ApplyState(IXbox360Controller pad, StatePacket s)
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

    public void Dispose()
    {
        _cts.Cancel();
        lock (_padLock)
        {
            foreach (var pad in _pads)
            {
                try { pad?.Disconnect(); } catch (InvalidOperationException) { /* never connected */ }
            }
        }
        _control.Dispose();
        _udp.Dispose();
        _vigem.Dispose();
        _cts.Dispose();
    }
}
