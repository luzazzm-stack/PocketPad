using System.Security.Cryptography;
using System.Text;

namespace PocketPad.Core;

/// <summary>
/// LAN discovery (PROTOCOL.md "Discovery"). A phone that already knows the
/// pairing code but not the PC's current address broadcasts a request carrying
/// the code's fingerprint; only the PC holding that code answers, and the
/// phone takes the address the answer came from. The code itself never goes
/// on the wire.
/// </summary>
public static class Discovery
{
    public const byte Magic = 0x44;   // 'D'
    public const byte Version = 0x01;
    public const byte KindRequest = 0x00;
    public const byte KindReply = 0x01;
    public const int Size = 8;

    /// <summary>First 4 bytes of SHA-256 of the UTF-8 pairing code.</summary>
    public static byte[] Fingerprint(string token) =>
        SHA256.HashData(Encoding.UTF8.GetBytes(token))[..4];

    public static byte[] Encode(byte kind, ReadOnlySpan<byte> fingerprint)
    {
        var b = new byte[Size];
        b[0] = Magic; b[1] = Version; b[2] = kind; b[3] = 0;
        fingerprint[..4].CopyTo(b.AsSpan(4));
        return b;
    }

    /// <summary>True when <paramref name="buf"/> is a discovery request for <paramref name="fingerprint"/>.</summary>
    public static bool IsRequestFor(ReadOnlySpan<byte> buf, ReadOnlySpan<byte> fingerprint) =>
        buf.Length == Size && buf[0] == Magic && buf[1] == Version && buf[2] == KindRequest
        && buf.Slice(4, 4).SequenceEqual(fingerprint[..4]);
}

/// <summary>
/// The PC's pairing code, kept on disk so a phone that paired once can
/// reconnect after the PC app (or the PC) restarts — even if the PC's
/// address changed, via <see cref="Discovery"/>.
/// </summary>
public static class PairingCode
{
    private static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "PocketPad", "pairing-code.txt");

    public static string LoadOrCreate()
    {
        try
        {
            var existing = File.ReadAllText(FilePath).Trim();
            if (existing.Length == 8 && existing.All(Uri.IsHexDigit)) return existing.ToLowerInvariant();
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }

        var code = RandomNumberGenerator.GetHexString(8, lowercase: true);
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            File.WriteAllText(FilePath, code);
        }
        catch (IOException) { /* still usable for this launch */ }
        catch (UnauthorizedAccessException) { }
        return code;
    }
}
