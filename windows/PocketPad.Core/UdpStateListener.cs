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
    private ushort _lastSeq;
    private bool _first = true;

    /// <summary>Fired for every accepted (fresh) pad state packet.</summary>
    public event Action<StatePacket, IPEndPoint>? StateReceived;

    /// <summary>Fired for every accepted (fresh) trackpad packet.</summary>
    public event Action<MousePacket, IPEndPoint>? MouseReceived;

    /// <summary>UTC time of the last accepted packet; for disconnect timeouts.</summary>
    public DateTime LastPacketUtc { get; private set; } = DateTime.MinValue;

    public UdpStateListener(int port = DefaultPort)
    {
        _udp = new UdpClient(port);
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

            // Dispatch on the magic byte: pad state or trackpad.
            ushort seq;
            bool isPad = StatePacket.TryDecode(result.Buffer, out var pad);
            MousePacket mouse = default;
            if (isPad) seq = pad.Seq;
            else if (MousePacket.TryDecode(result.Buffer, out mouse)) seq = mouse.Seq;
            else continue;

            if (!_first && !StatePacket.IsNewer(seq, _lastSeq))
                continue; // stale or duplicate

            _first = false;
            _lastSeq = seq;
            LastPacketUtc = DateTime.UtcNow;

            if (isPad) StateReceived?.Invoke(pad, result.RemoteEndPoint);
            else MouseReceived?.Invoke(mouse, result.RemoteEndPoint);
        }
    }

    /// <summary>Reset sequence tracking (call when a new client connects).</summary>
    public void ResetSequence() => _first = true;

    public void Dispose() => _udp.Dispose();
}
