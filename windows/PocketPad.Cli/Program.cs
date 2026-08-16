using PocketPad.Core;

// Developer console host for the PocketPad Link engine. End users get the
// WPF app (PocketPad.App); this exists for quick headless testing.

Console.Title = "PocketPad (dev console)";
Console.WriteLine();
Console.WriteLine("  P O C K E T P A D   dev console");
Console.WriteLine("  ================================");

LinkSession session;
try
{
    session = new LinkSession();
}
catch (LinkSession.DriverMissingException e)
{
    Console.WriteLine();
    Console.WriteLine("  Could not start the virtual controller.");
    Console.WriteLine("  The ViGEm driver is missing. Install it from:");
    Console.WriteLine("      https://github.com/nefarius/ViGEmBus/releases");
    Console.WriteLine($"  (details: {e.InnerException?.Message})");
    return 1;
}

using var _ = session;
Console.WriteLine("  Controller ready.");
Console.WriteLine();
Console.WriteLine("  Open PocketPad on your phone and enter:");
Console.WriteLine();
Console.ForegroundColor = ConsoleColor.Cyan;
Console.WriteLine($"      PC address   {LinkSession.PrimaryIPv4()}");
Console.WriteLine($"      Code         {session.Token}");
Console.ResetColor();
Console.WriteLine();
Console.WriteLine("  Waiting for the phone...        (Ctrl+C to stop)");

session.PhoneConnected += (player, name, ep) =>
{
    Console.ForegroundColor = ConsoleColor.Green;
    Console.WriteLine($"  CONNECTED   P{player + 1}  {name}  ({ep.Address})");
    Console.ResetColor();
};
session.PhoneDisconnected += (player, remaining) =>
{
    Console.ForegroundColor = ConsoleColor.Yellow;
    Console.WriteLine($"  P{player + 1} disconnected ({remaining} still connected).");
    Console.ResetColor();
};
session.LatencyReported += (player, ms) =>
    Console.Title = $"PocketPad (dev console) — P{player + 1}: {ms} ms";

using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };
try
{
    await session.RunAsync().WaitAsync(cts.Token);
}
catch (OperationCanceledException)
{
    // normal shutdown
}
Console.WriteLine("  Bye.");
return 0;
