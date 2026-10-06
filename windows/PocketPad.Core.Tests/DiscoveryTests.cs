using PocketPad.Core;
using Xunit;

namespace PocketPad.Core.Tests;

public class DiscoveryTests
{
    [Fact]
    public void Encode_WritesExactWireFormat()
    {
        // Golden bytes pinned to PROTOCOL.md "Discovery" — mirrored by the Kotlin test.
        var fp = Discovery.Fingerprint("ed8df5da");
        Assert.Equal(new byte[] { 0xBF, 0xA9, 0x53, 0x81 }, fp);

        Assert.Equal(
            new byte[] { 0x44, 0x01, 0x00, 0x00, 0xBF, 0xA9, 0x53, 0x81 },
            Discovery.Encode(Discovery.KindRequest, fp));
        Assert.Equal(
            new byte[] { 0x44, 0x01, 0x01, 0x00, 0xBF, 0xA9, 0x53, 0x81 },
            Discovery.Encode(Discovery.KindReply, fp));
    }

    [Fact]
    public void IsRequestFor_MatchesOnlyOwnFingerprintRequests()
    {
        var mine = Discovery.Fingerprint("ed8df5da");
        var other = Discovery.Fingerprint("00000000");

        Assert.True(Discovery.IsRequestFor(Discovery.Encode(Discovery.KindRequest, mine), mine));
        Assert.False(Discovery.IsRequestFor(Discovery.Encode(Discovery.KindRequest, other), mine));
        Assert.False(Discovery.IsRequestFor(Discovery.Encode(Discovery.KindReply, mine), mine));
        Assert.False(Discovery.IsRequestFor(new byte[16], mine)); // a pad packet is never a request
    }
}
