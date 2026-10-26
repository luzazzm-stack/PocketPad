using System.Net;
using System.Net.Sockets;

namespace PocketPad.Core;

/// <summary>
/// USB transport for the pad/trackpad packets (PROTOCOL.md "USB").
///
/// adb reverse can only tunnel TCP, so a phone on the cable sends the same 16/12-byte
/// packets over a TCP stream instead of UDP, each prefixed by a u8 length. Listens on
/// loopback only: everything arriving here came through a phone's adb tunnel.
/// </summary>
public sealed class StreamStateListener : IDisposable
{
    public const int DefaultPort = 46823;

    private readonly TcpListener _listener;
    private readonly UdpStateListener _sink;

    public StreamStateListener(UdpStateListener sink, int port = DefaultPort)
    {
        _sink = sink;
        _listener = new TcpListener(IPAddress.Loopback, port);
        _listener.Start();
    }

    public async Task RunAsync(CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                TcpClient client;
                try
                {
                    client = await _listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                _ = PumpAsync(client, ct);
            }
        }
        finally
        {
            _listener.Stop();
        }
    }

    private async Task PumpAsync(TcpClient client, CancellationToken ct)
    {
        using var _ = client;
        client.NoDelay = true;
        var from = (IPEndPoint)client.Client.RemoteEndPoint!;
        var stream = client.GetStream();
        var len = new byte[1];
        try
        {
            while (!ct.IsCancellationRequested)
            {
                await stream.ReadExactlyAsync(len, ct).ConfigureAwait(false);
                int n = len[0];
                if (n == 0 || n > 64)
                    return;                       // not our framing - drop the connection
                var frame = new byte[n];
                await stream.ReadExactlyAsync(frame, ct).ConfigureAwait(false);
                _sink.Handle(frame, from);
            }
        }
        catch (Exception) when (!ct.IsCancellationRequested)
        {
            // phone unplugged or app closed
        }
    }

    public void Dispose() => _listener.Stop();
}
