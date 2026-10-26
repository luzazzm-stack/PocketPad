using System.Net;
using System.Net.Sockets;

namespace PocketPad.Core;

/// <summary>
/// Listens on UDP (default port 46821) for both packet types the phone sends —
/// 16-byte pad state and 12-byte trackpad — dispatching on the magic byte.
/// Drops stale/duplicate packets by sequence number (one shared seq space).
/// </summary>
public sealed class UdpStateListener : IDisposable
{
    public const int DefaultPort = 46821;

    private readonly UdpClient _udp;
    private readonly ushort[] _lastSeq = new ushort[StatePacket.MaxPlayers];
    private readonly bool[] _first = { true, true, true, true };

    /// <summary>Fired for every accepted (fresh) pad state packet.</summary>
    public event Action<StatePacket, IPEndPoint>? StateReceived;

    /// <summary>Fired for every accepted (fresh) trackpad packet.</summary>
    public event Action<MousePacket, IPEndPoint>? MouseReceived;

    /// <summary>UTC time of the last accepted packet; for disconnect timeouts.</summary>
    public DateTime LastPacketUtc { get; private set; } = DateTime.MinValue;

    private readonly byte[]? _discoveryFingerprint;

    /// <param name="discoveryFingerprint">
    /// When set, answer discovery requests carrying this fingerprint
    /// (PROTOCOL.md "Discovery") so a paired phone can find this PC again.
    /// </param>
    public UdpStateListener(int port = DefaultPort, byte[]? discoveryFingerprint = null)
    {
        _udp = new UdpClient(port);
        _discoveryFingerprint = discoveryFingerprint;
    }

    /// <summary>Receive loop; run until cancellation. Safe to call once.</summary>
    public async Task RunAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try
            {
                result = await _udp.ReceiveAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (SocketException)
            {
                // Remote ICMP port-unreachable can surface here on Windows; keep listening.
                continue;
            }

            if (_discoveryFingerprint is { } fp && Discovery.IsRequestFor(result.Buffer, fp))
            {
                try
                {
                    await _udp.SendAsync(Discovery.Encode(Discovery.KindReply, fp), result.RemoteEndPoint, ct)
                        .ConfigureAwait(false);
                }
                catch (SocketException) { /* phone went away; it will retry */ }
                catch (OperationCanceledException) { break; }
                continue;
            }

            Handle(result.Buffer, result.RemoteEndPoint);
        }
    }

    private readonly object _seqLock = new();

    /// <summary>
    /// Accept one pad/trackpad packet from any transport - UDP here, or a USB stream
    /// (<see cref="StreamStateListener"/>). Dispatches on the magic byte and drops stale
    /// packets per player. Thread-safe: the two transports call it from different threads.
    /// </summary>
    public void Handle(byte[] buffer, IPEndPoint from)
    {
        ushort seq;
        byte player;
        bool isPad = StatePacket.TryDecode(buffer, out var pad);
        MousePacket mouse = default;
        if (isPad) { seq = pad.Seq; player = pad.Player; }
        else if (MousePacket.TryDecode(buffer, out mouse)) { seq = mouse.Seq; player = mouse.Player; }
        else return;

        lock (_seqLock)
        {
            if (!_first[player] && !StatePacket.IsNewer(seq, _lastSeq[player]))
                return; // stale or duplicate for this player
            _first[player] = false;
            _lastSeq[player] = seq;
            LastPacketUtc = DateTime.UtcNow;
        }

        if (isPad) StateReceived?.Invoke(pad, from);
        else MouseReceived?.Invoke(mouse, from);
    }

    /// <summary>Reset one player's sequence tracking (call when that phone connects).</summary>
    public void ResetSequence(int player) => _first[player] = true;

    public void Dispose() => _udp.Dispose();
}
