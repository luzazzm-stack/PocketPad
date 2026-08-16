using System.Buffers.Binary;

namespace PocketPad.Core;

/// <summary>
/// Button bits of the 16-byte state packet (PROTOCOL.md v1).
/// </summary>
[Flags]
public enum PadButtons : ushort
{
    None  = 0,
    A     = 1 << 0,
    B     = 1 << 1,
    X     = 1 << 2,
    Y     = 1 << 3,
    LB    = 1 << 4,
    RB    = 1 << 5,
    Back  = 1 << 6,
    Start = 1 << 7,
    L3    = 1 << 8,
    R3    = 1 << 9,
    LT    = 1 << 10,
    RT    = 1 << 11,
}

/// <summary>
/// D-pad direction, clockwise from Up. 0 = neutral (PROTOCOL.md v1).
/// </summary>
public enum Dpad : byte
{
    Neutral   = 0,
    Up        = 1,
    UpRight   = 2,
    Right     = 3,
    DownRight = 4,
    Down      = 5,
    DownLeft  = 6,
    Left      = 7,
    UpLeft    = 8,
}

/// <summary>
/// One 16-byte controller state packet as defined in protocol/PROTOCOL.md v1.
/// All multi-byte fields little-endian.
/// </summary>
public readonly record struct StatePacket(
    ushort Seq,
    PadButtons Buttons,
    Dpad Dpad,
    short Lx,
    short Ly,
    short Rx,
    short Ry,
    byte Player = 0)
{
    public const int MaxPlayers = 4;

    public const byte Magic = 0x50; // 'P'
    public const byte Version = 0x01;
    public const int Size = 16;

    /// <summary>Encode into a 16-byte buffer. Throws if the buffer is too small.</summary>
    public void Encode(Span<byte> dest)
    {
        if (dest.Length < Size)
            throw new ArgumentException($"Need {Size} bytes, got {dest.Length}", nameof(dest));

        dest[0] = Magic;
        dest[1] = Version;
        BinaryPrimitives.WriteUInt16LittleEndian(dest[2..], Seq);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[4..], (ushort)Buttons);
        dest[6] = (byte)Dpad;
        dest[7] = Player;
        BinaryPrimitives.WriteInt16LittleEndian(dest[8..], Lx);
        BinaryPrimitives.WriteInt16LittleEndian(dest[10..], Ly);
        BinaryPrimitives.WriteInt16LittleEndian(dest[12..], Rx);
        BinaryPrimitives.WriteInt16LittleEndian(dest[14..], Ry);
    }

    /// <summary>
    /// Try to decode a packet. Returns false on wrong size, magic, version,
    /// or an out-of-range dpad value.
    /// </summary>
    public static bool TryDecode(ReadOnlySpan<byte> src, out StatePacket packet)
    {
        packet = default;
        if (src.Length != Size || src[0] != Magic || src[1] != Version)
            return false;

        byte dpad = src[6];
        if (dpad > (byte)Dpad.UpLeft)
            return false;
        byte player = src[7];
        if (player >= MaxPlayers)
            return false;

        packet = new StatePacket(
            Seq: BinaryPrimitives.ReadUInt16LittleEndian(src[2..]),
            Buttons: (PadButtons)BinaryPrimitives.ReadUInt16LittleEndian(src[4..]),
            Dpad: (Dpad)dpad,
            Lx: BinaryPrimitives.ReadInt16LittleEndian(src[8..]),
            Ly: BinaryPrimitives.ReadInt16LittleEndian(src[10..]),
            Rx: BinaryPrimitives.ReadInt16LittleEndian(src[12..]),
            Ry: BinaryPrimitives.ReadInt16LittleEndian(src[14..]),
            Player: player);
        return true;
    }

    /// <summary>
    /// True if <paramref name="seq"/> is newer than <paramref name="last"/>,
    /// treating the u16 space as a circle so wraparound (65535 → 0) still
    /// counts as newer. Used to drop stale/reordered UDP packets.
    /// </summary>
    public static bool IsNewer(ushort seq, ushort last)
        => (ushort)(seq - last) is > 0 and < 0x8000;
}
