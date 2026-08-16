namespace PocketPad.App;

public partial class App : System.Windows.Application
{
    private const string MutexName = "PocketPadForPC_SingleInstance";
    private const string ShowEventName = "PocketPadForPC_ShowWindow";

    private Mutex? _instanceMutex;
    private EventWaitHandle? _showEvent;

    protected override void OnStartup(System.Windows.StartupEventArgs e)
    {
        base.OnStartup(e);

        // Single instance: double-clicking the icon while PocketPad is in the
        // tray must bring the existing window back, not silently do nothing
        // (a second copy could never bind the ports anyway).
        _instanceMutex = new Mutex(initiallyOwned: true, MutexName, out bool isFirst);
        if (!isFirst)
        {
            try
            {
                using var wake = EventWaitHandle.OpenExisting(ShowEventName);
                wake.Set(); // tell the running instance to show its window
            }
            catch (WaitHandleCannotBeOpenedException) { /* races app shutdown; nothing to do */ }
            Shutdown();
            return;
        }

        _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);

        var window = new MainWindow();

        // Waits forever, waking once per "please show yourself" signal.
        var waiter = new Thread(() =>
        {
            while (true)
            {
                try { _showEvent.WaitOne(); }
                catch (ObjectDisposedException) { return; }
                Dispatcher.Invoke(window.ShowFromSecondLaunch);
            }
        })
        { IsBackground = true, Name = "PocketPad.ShowSignal" };
        waiter.Start();

        window.Show();
    }

    protected override void OnExit(System.Windows.ExitEventArgs e)
    {
        _showEvent?.Dispose();
        _instanceMutex?.Dispose();
        base.OnExit(e);
    }
}
