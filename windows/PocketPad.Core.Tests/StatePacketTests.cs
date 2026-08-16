using PocketPad.Core;
using Xunit;

namespace PocketPad.Core.Tests;

public class StatePacketTests
{
    [Fact]
    public void EncodeDecode_RoundTrips()
    {
        var original = new StatePacket(
            Seq: 12345,
            Buttons: PadButtons.A | PadButtons.RB | PadButtons.Start | PadButtons.LT,
            Dpad: Dpad.DownLeft,
            Lx: -32768, Ly: 32767, Rx: -1, Ry: 12345,
            Player: 3);

        Span<byte> buf = stackalloc byte[StatePacket.Size];
        original.Encode(buf);

        Assert.True(StatePacket.TryDecode(buf, out var decoded));
        Assert.Equal(original, decoded);
    }

    [Fact]
    public void Encode_WritesExactWireFormat()
    {
        // Golden bytes pinned to PROTOCOL.md v1 — if this test breaks, the
        // Android encoder must change too.
        var p = new StatePacket(
            Seq: 0x0201,
            Buttons: PadButtons.A | PadButtons.Y, // 0b1001 = 0x0009
            Dpad: Dpad.Right,                     // 3
            Lx: 0x1122, Ly: -2, Rx: 0, Ry: 0x7FFF,
            Player: 2);

        var buf = new byte[StatePacket.Size];
        p.Encode(buf);

        Assert.Equal(new byte[]
        {
            0x50, 0x01,             // magic 'P', version 1
            0x01, 0x02,             // seq 0x0201 LE
            0x09, 0x00,             // buttons LE
            0x03,                   // dpad Right
            0x02,                   // player 2
            0x22, 0x11,             // lx LE
            0xFE, 0xFF,             // ly = -2 LE
            0x00, 0x00,             // rx
            0xFF, 0x7F,             // ry = 32767 LE
        }, buf);
    }

    [Fact]
    public void TryDecode_RejectsOutOfRangePlayer()
    {
        var buf = new byte[StatePacket.Size];
        new StatePacket(1, PadButtons.None, Dpad.Neutral, 0, 0, 0, 0).Encode(buf);
        buf[7] = 4; // MaxPlayers is 4, so valid ids are 0-3
        Assert.False(StatePacket.TryDecode(buf, out _));
    }

    [Theory]
    [InlineData(0)]   // empty
    [InlineData(15)]  // short
    [InlineData(17)]  // long
    public void TryDecode_RejectsWrongSize(int size)
    {
        Assert.False(StatePacket.TryDecode(new byte[size], out _));
    }

    [Fact]
    public void TryDecode_RejectsBadMagicAndVersion()
    {
        var buf = new byte[StatePacket.Size];
        new StatePacket(1, PadButtons.None, Dpad.Neutral, 0, 0, 0, 0).Encode(buf);

        buf[0] = 0x51; // wrong magic
        Assert.False(StatePacket.TryDecode(buf, out _));

        buf[0] = StatePacket.Magic;
        buf[1] = 0x02; // wrong version
        Assert.False(StatePacket.TryDecode(buf, out _));
    }

    [Fact]
    public void TryDecode_RejectsOutOfRangeDpad()
    {
        var buf = new byte[StatePacket.Size];
        new StatePacket(1, PadButtons.None, Dpad.Neutral, 0, 0, 0, 0).Encode(buf);
        buf[6] = 9; // > UpLeft
        Assert.False(StatePacket.TryDecode(buf, out _));
    }

    [Theory]
    [InlineData((ushort)5, (ushort)4, true)]        // simple newer
    [InlineData((ushort)4, (ushort)5, false)]       // older
    [InlineData((ushort)5, (ushort)5, false)]       // duplicate
    [InlineData((ushort)2, (ushort)65530, true)]    // wraparound: 65530 → 2 is newer
    [InlineData((ushort)65530, (ushort)2, false)]   // reverse of wraparound is stale
    public void IsNewer_HandlesWraparound(ushort seq, ushort last, bool expected)
    {
        Assert.Equal(expected, StatePacket.IsNewer(seq, last));
    }
}
