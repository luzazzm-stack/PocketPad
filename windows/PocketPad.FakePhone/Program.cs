using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using PocketPad.Core;

// Dev utility: pretends to be a PocketPad phone, for multi-device testing
// without owning four phones. Speaks the real protocol.
// Usage: PocketPad.FakePhone <host> <token> <name> <button:A|B|X|Y> [seconds]

if (args.Length < 4)
{
    Console.WriteLine("usage: FakePhone <host> <token> <name> <A|B|X|Y> [seconds]");
    return 2;
}
string host = args[0], token = args[1], name = args[2];
var button = args[3].ToUpperInvariant() switch
{
    "A" => PadButtons.A, "B" => PadButtons.B, "X" => PadButtons.X, "Y" => PadButtons.Y,
    _ => PadButtons.A,
};
int seconds = args.Length > 4 && int.TryParse(args[4], out var s) ? s : 8;

// ---- handshake ----
using var tcp = new TcpClient();
await tcp.ConnectAsync(host, ControlServer.DefaultPort);
tcp.NoDelay = true;
var stream = tcp.GetStream();
var reader = new StreamReader(stream, Encoding.UTF8);

async Task Send(object msg)
{
    var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(msg) + "\n");
    await stream.WriteAsync(bytes);
}

await Send(new { t = "hello", v = 1, name, token });
var replyLine = await reader.ReadLineAsync() ?? throw new IOException("closed");
var reply = JsonDocument.Parse(replyLine).RootElement;
if (reply.GetProperty("t").GetString() != "welcome")
{
    Console.WriteLine($"REJECTED: {replyLine}");
    return 1;
}
byte player = (byte)(reply.TryGetProperty("player", out var pl) ? pl.GetInt32() : 0);
int udpPort = reply.GetProperty("udp").GetInt32();
Console.WriteLine($"WELCOME player={player}");

// ---- hold the button, 125 Hz, for the duration ----
using var udp = new UdpClient();
udp.Connect(host, udpPort);
var buf = new byte[StatePacket.Size];
ushort seq = 0;
var deadline = DateTime.UtcNow.AddSeconds(seconds);
while (DateTime.UtcNow < deadline)
{
    var held = new StatePacket(seq++, button, Dpad.Neutral, 0, 0, 0, 0, player);
    held.Encode(buf);
    await udp.SendAsync(buf);
    await Task.Delay(8);
}

// release + goodbye
new StatePacket(seq, PadButtons.None, Dpad.Neutral, 0, 0, 0, 0, player).Encode(buf);
await udp.SendAsync(buf);
await Send(new { t = "bye" });
Console.WriteLine($"done (player {player}, held {button})");
return 0;
