using PocketPad.Core;
using Xunit;

namespace PocketPad.Core.Tests;

public class MousePacketTests
{
    [Fact]
    public void EncodeDecode_RoundTrips()
    {
        var original = new MousePacket(
            Seq: 4242,
            Buttons: MouseButtons.Left | MouseButtons.Middle,
            Dx: -1200, Dy: 900, Wheel: -3);

        Span<byte> buf = stackalloc byte[MousePacket.Size];
        original.Encode(buf);

        Assert.True(MousePacket.TryDecode(buf, out var decoded));
        Assert.Equal(original, decoded);
    }

    [Fact]
    public void Encode_WritesExactWireFormat()
    {
        // Golden bytes pinned to PROTOCOL.md v1 — mirrored by the Kotlin test.
        var p = new MousePacket(
            Seq: 0x0201,
            Buttons: MouseButtons.Right,     // 0x02
            Dx: -2, Dy: 0x1122, Wheel: -1);

        var buf = new byte[MousePacket.Size];
        p.Encode(buf);

        Assert.Equal(new byte[]
        {
            0x4D, 0x01,             // magic 'M', version 1
            0x01, 0x02,             // seq LE
            0x02,                   // buttons: Right
            0x00,                   // reserved
            0xFE, 0xFF,             // dx = -2 LE
            0x22, 0x11,             // dy LE
            0xFF,                   // wheel = -1
            0x00,                   // reserved
        }, buf);
    }

    [Fact]
    public void TryDecode_RejectsPadPacket()
    {
        // A 16-byte pad packet must not be mistaken for a trackpad packet.
        var buf = new byte[StatePacket.Size];
        new StatePacket(1, PadButtons.A, Dpad.Up, 0, 0, 0, 0).Encode(buf);
        Assert.False(MousePacket.TryDecode(buf, out _));
    }

    [Fact]
    public void TryDecode_IgnoresUndefinedButtonBits()
    {
        var buf = new byte[MousePacket.Size];
        new MousePacket(1, MouseButtons.Left, 0, 0, 0).Encode(buf);
        buf[4] = 0xFF; // all bits set
        Assert.True(MousePacket.TryDecode(buf, out var p));
        Assert.Equal(MouseButtons.Left | MouseButtons.Right | MouseButtons.Middle, p.Buttons);
    }

    [Fact]
    public void StatePacket_TryDecode_RejectsMousePacket()
    {
        var buf = new byte[MousePacket.Size];
        new MousePacket(1, MouseButtons.Left, 0, 0, 0).Encode(buf);
        Assert.False(StatePacket.TryDecode(buf, out _));
    }
}
