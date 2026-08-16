using System.Runtime.InteropServices;

// Dev utility: polls an XInput slot the same way a game does and prints any
// state change. Proves the whole chain phone -> Link -> ViGEm -> XInput.
// Usage: PocketPad.XInputProbe [slot] [seconds]

uint slot = args.Length > 0 && uint.TryParse(args[0], out var sl) ? sl : 0;
int seconds = args.Length > 1 && int.TryParse(args[1], out var s) ? s : 10;

Console.WriteLine($"Polling XInput slot {slot} for {seconds}s — press buttons on the phone.");

var deadline = DateTime.UtcNow.AddSeconds(seconds);
XInputState last = default;
bool haveLast = false;
int changes = 0;

while (DateTime.UtcNow < deadline)
{
    if (XInputGetState(slot, out var state) != 0)
    {
        Console.WriteLine($"No controller in slot {slot}.");
        return 2;
    }

    if (!haveLast || state.Gamepad.Buttons != last.Gamepad.Buttons
        || state.Gamepad.LeftThumbX != last.Gamepad.LeftThumbX
        || state.Gamepad.LeftThumbY != last.Gamepad.LeftThumbY
        || state.Gamepad.LeftTrigger != last.Gamepad.LeftTrigger
        || state.Gamepad.RightTrigger != last.Gamepad.RightTrigger)
    {
        var g = state.Gamepad;
        Console.WriteLine(
            $"buttons={Describe(g.Buttons),-28} " +
            $"LX={g.LeftThumbX,6} LY={g.LeftThumbY,6} LT={g.LeftTrigger,3} RT={g.RightTrigger,3}");
        last = state;
        haveLast = true;
        changes++;
    }
    Thread.Sleep(4);
}

Console.WriteLine($"Done. {changes} state changes observed.");
return changes > 1 ? 0 : 1;

static string Describe(ushort b)
{
    if (b == 0) return "(none)";
    var names = new List<string>();
    void T(ushort bit, string n) { if ((b & bit) != 0) names.Add(n); }
    T(0x0001, "Up"); T(0x0002, "Down"); T(0x0004, "Left"); T(0x0008, "Right");
    T(0x0010, "Start"); T(0x0020, "Back"); T(0x0040, "L3"); T(0x0080, "R3");
    T(0x0100, "LB"); T(0x0200, "RB");
    T(0x1000, "A"); T(0x2000, "B"); T(0x4000, "X"); T(0x8000, "Y");
    return string.Join("+", names);
}

[DllImport("xinput1_4.dll", EntryPoint = "XInputGetState")]
static extern uint XInputGetState(uint index, out XInputState state);

[StructLayout(LayoutKind.Sequential)]
struct XInputState
{
    public uint PacketNumber;
    public XInputGamepad Gamepad;
}

[StructLayout(LayoutKind.Sequential)]
struct XInputGamepad
{
    public ushort Buttons;
    public byte LeftTrigger;
    public byte RightTrigger;
    public short LeftThumbX;
    public short LeftThumbY;
    public short RightThumbX;
    public short RightThumbY;
}
