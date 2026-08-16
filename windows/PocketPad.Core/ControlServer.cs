using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PocketPad.Core;

/// <summary>
/// TCP control channel (default port 46822). JSON lines per PROTOCOL.md v1:
/// hello/welcome/reject handshake, ping/pong for the latency meter, bye.
/// One client at a time; a second connection is rejected until the first leaves.
/// </summary>
public sealed class ControlServer : IDisposable
{
    public const int DefaultPort = 46822;
    private const int ProtocolVersion = 1;

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly TcpListener _listener;
    private readonly string _token;
    private int _hasClient; // 0/1, interlocked

    /// <summary>Fired when a phone completes the handshake. Arg: device name.</summary>
    public event Action<string, IPEndPoint>? ClientConnected;

    /// <summary>Fired when the connected phone disconnects (bye, EOF, or error).</summary>
    public event Action? ClientDisconnected;

    public ControlServer(string token, int port = DefaultPort)
    {
        _token = token;
        _listener = new TcpListener(IPAddress.Any, port);
    }

    public async Task RunAsync(CancellationToken ct)
    {
        _listener.Start();
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
                _ = HandleClientAsync(client, ct); // fire-and-forget per connection
            }
        }
        finally
        {
            _listener.Stop();
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken ct)
    {
        using var _ = client;
        client.NoDelay = true;
        var remote = (IPEndPoint)client.Client.RemoteEndPoint!;
        var stream = client.GetStream();
        using var reader = new StreamReader(stream, Encoding.UTF8, false, 1024, leaveOpen: true);

        bool claimed = false;
        try
        {
            // ---- handshake ----
            var hello = await ReadMsgAsync(reader, ct).ConfigureAwait(false);
            if (hello is null || hello.T != "hello")
                return;

            if (hello.V != ProtocolVersion)
            {
                await SendAsync(stream, new Msg { T = "reject", Reason = "version" }, ct).ConfigureAwait(false);
                return;
            }
            if (hello.Token != _token)
            {
                await SendAsync(stream, new Msg { T = "reject", Reason = "token" }, ct).ConfigureAwait(false);
                return;
            }
            if (Interlocked.CompareExchange(ref _hasClient, 1, 0) != 0)
            {
                await SendAsync(stream, new Msg { T = "reject", Reason = "busy" }, ct).ConfigureAwait(false);
                return;
            }
            claimed = true;

            await SendAsync(stream, new Msg { T = "welcome", V = ProtocolVersion, Udp = UdpStateListener.DefaultPort }, ct)
                .ConfigureAwait(false);
            ClientConnected?.Invoke(hello.Name ?? "phone", remote);

            // ---- session loop: answer pings until bye/EOF ----
            while (!ct.IsCancellationRequested)
            {
                var msg = await ReadMsgAsync(reader, ct).ConfigureAwait(false);
                if (msg is null || msg.T == "bye")
                    break;
                if (msg.T == "ping")
                    await SendAsync(stream, new Msg { T = "pong", Id = msg.Id, Ts = msg.Ts }, ct).ConfigureAwait(false);
            }
        }
        catch (Exception) when (!ct.IsCancellationRequested)
        {
            // connection dropped; fall through to cleanup
        }
        finally
        {
            if (claimed)
            {
                Interlocked.Exchange(ref _hasClient, 0);
                ClientDisconnected?.Invoke();
            }
        }
    }

    private static async Task<Msg?> ReadMsgAsync(StreamReader reader, CancellationToken ct)
    {
        var line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
        if (line is null) return null;
        try
        {
            return JsonSerializer.Deserialize<Msg>(line, JsonOpts);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static async Task SendAsync(NetworkStream stream, Msg msg, CancellationToken ct)
    {
        var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(msg, JsonOpts) + "\n");
        await stream.WriteAsync(bytes, ct).ConfigureAwait(false);
    }

    public void Dispose() => _listener.Dispose();

    /// <summary>Wire shape of every control message (PROTOCOL.md v1).</summary>
    public sealed class Msg
    {
        [JsonPropertyName("t")] public string? T { get; set; }
        [JsonPropertyName("v")] public int? V { get; set; }
        [JsonPropertyName("name")] public string? Name { get; set; }
        [JsonPropertyName("token")] public string? Token { get; set; }
        [JsonPropertyName("udp")] public int? Udp { get; set; }
        [JsonPropertyName("reason")] public string? Reason { get; set; }
        [JsonPropertyName("id")] public long? Id { get; set; }
        [JsonPropertyName("ts")] public long? Ts { get; set; }
    }
}
