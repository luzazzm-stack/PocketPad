using System.Runtime.InteropServices;

namespace PocketPad.Core;

/// <summary>
/// Turns trackpad packets into real Windows mouse input via SendInput —
/// the same path a physical mouse driver uses, so every app accepts it.
/// Buttons are level-triggered on the wire, so we emit down/up on transitions.
/// </summary>
public sealed class MouseInjector
{
    private MouseButtons _last = MouseButtons.None;

    public void Apply(MousePacket p)
    {
        var inputs = new List<INPUT>(5);

        if (p.Dx != 0 || p.Dy != 0)
            inputs.Add(Mouse(p.Dx, p.Dy, 0, MOUSEEVENTF_MOVE));

        AddTransition(inputs, p.Buttons, MouseButtons.Left, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP);
        AddTransition(inputs, p.Buttons, MouseButtons.Right, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP);
        AddTransition(inputs, p.Buttons, MouseButtons.Middle, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP);

        if (p.Wheel != 0)
            inputs.Add(Mouse(0, 0, p.Wheel * WHEEL_DELTA, MOUSEEVENTF_WHEEL));

        _last = p.Buttons;

        if (inputs.Count > 0)
        {
            var arr = inputs.ToArray();
            SendInput((uint)arr.Length, arr, Marshal.SizeOf<INPUT>());
        }
    }

    /// <summary>Release anything still held (call on disconnect).</summary>
    public void ReleaseAll() => Apply(new MousePacket(0, MouseButtons.None, 0, 0, 0));

    private void AddTransition(List<INPUT> inputs, MouseButtons now, MouseButtons bit, uint down, uint up)
    {
        bool wasDown = _last.HasFlag(bit);
        bool isDown = now.HasFlag(bit);
        if (isDown && !wasDown) inputs.Add(Mouse(0, 0, 0, down));
        else if (!isDown && wasDown) inputs.Add(Mouse(0, 0, 0, up));
    }

    private static INPUT Mouse(int dx, int dy, int data, uint flags) => new()
    {
        type = INPUT_MOUSE,
        mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = data, dwFlags = flags },
    };

    private const uint INPUT_MOUSE = 0;
    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const int WHEEL_DELTA = 120;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int size);

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public MOUSEINPUT mi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public int mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }
}
