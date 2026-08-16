using System.Net;
using System.Net.Sockets;

namespace PocketPad.Core;

/// <summary>
/// Listens for 16-byte state packets on UDP (default port 46821), drops
/// stale/duplicate packets by sequence number, and raises StateReceived
/// for each accepted packet.
/// </summary>
public sealed class UdpStateListener : IDisposable
{
    public const int DefaultPort = 46821;

    private readonly UdpClient _udp;
    private ushort _lastSeq;
    private bool _first = true;

    /// <summary>Fired for every accepted (fresh) state packet.</summary>
    public event Action<StatePacket, IPEndPoint>? StateReceived;

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

            if (!StatePacket.TryDecode(result.Buffer, out var packet))
                continue;

            if (!_first && !StatePacket.IsNewer(packet.Seq, _lastSeq))
                continue; // stale or duplicate

            _first = false;
            _lastSeq = packet.Seq;
            LastPacketUtc = DateTime.UtcNow;
            StateReceived?.Invoke(packet, result.RemoteEndPoint);
        }
    }

    /// <summary>Reset sequence tracking (call when a new client connects).</summary>
    public void ResetSequence() => _first = true;

    public void Dispose() => _udp.Dispose();
}
