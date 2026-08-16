using System.Buffers.Binary;

namespace PocketPad.Core;

[Flags]
public enum MouseButtons : byte
{
    None   = 0,
    Left   = 1 << 0,
    Right  = 1 << 1,
    Middle = 1 << 2,
}

/// <summary>
/// One 12-byte trackpad packet as defined in protocol/PROTOCOL.md v1.
/// Dx/Dy/Wheel are relative deltas already consumed by the sender.
/// </summary>
public readonly record struct MousePacket(
    ushort Seq,
    MouseButtons Buttons,
    short Dx,
    short Dy,
    sbyte Wheel,
    byte Player = 0)
{
    public const byte Magic = 0x4D; // 'M'
    public const byte Version = 0x01;
    public const int Size = 12;

    public void Encode(Span<byte> dest)
    {
        if (dest.Length < Size)
            throw new ArgumentException($"Need {Size} bytes, got {dest.Length}", nameof(dest));

        dest[0] = Magic;
        dest[1] = Version;
        BinaryPrimitives.WriteUInt16LittleEndian(dest[2..], Seq);
        dest[4] = (byte)Buttons;
        dest[5] = Player;
        BinaryPrimitives.WriteInt16LittleEndian(dest[6..], Dx);
        BinaryPrimitives.WriteInt16LittleEndian(dest[8..], Dy);
        dest[10] = (byte)Wheel;
        dest[11] = 0;
    }

    public static bool TryDecode(ReadOnlySpan<byte> src, out MousePacket packet)
    {
        packet = default;
        if (src.Length != Size || src[0] != Magic || src[1] != Version)
            return false;
        if (src[5] >= StatePacket.MaxPlayers)
            return false;

        packet = new MousePacket(
            Seq: BinaryPrimitives.ReadUInt16LittleEndian(src[2..]),
            Buttons: (MouseButtons)(src[4] & 0b111),
            Dx: BinaryPrimitives.ReadInt16LittleEndian(src[6..]),
            Dy: BinaryPrimitives.ReadInt16LittleEndian(src[8..]),
            Wheel: (sbyte)src[10],
            Player: src[5]);
        return true;
    }
}
