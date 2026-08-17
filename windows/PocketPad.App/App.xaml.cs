namespace PocketPad.App;

public partial class App : System.Windows.Application
{
    private const string MutexName = "PocketPadForPC_SingleInstance";
    private const string ShowEventName = "PocketPadForPC_ShowWindow";

    private Mutex? _instanceMutex;
    private EventWaitHandle? _showEvent;
    private Thread? _waiter;
    private volatile bool _shuttingDown;

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
        if (window.StartupFailed)
        {
            // The ports were taken; the window has already explained that and
            // queued Shutdown(). Showing it, or starting the waiter, would only
            // put UI on screen on the way back out.
            return;
        }

        // Waits forever, waking once per "please show yourself" signal.
        _waiter = new Thread(() =>
        {
            while (true)
            {
                try { _showEvent.WaitOne(); }
                catch (ObjectDisposedException) { return; }

                if (_shuttingDown) return;

                // The dispatcher can begin shutting down between the signal and
                // the call below — "Quit completely", or a startup bail-out. An
                // Invoke against it then throws on this background thread, where
                // nothing catches it, and an unhandled exception there takes the
                // whole process down with a crash dialog instead of exiting.
                if (Dispatcher.HasShutdownStarted || Dispatcher.HasShutdownFinished) return;
                try
                {
                    Dispatcher.Invoke(window.ShowFromSecondLaunch);
                }
                catch (OperationCanceledException) { return; } // incl. TaskCanceledException
                catch (InvalidOperationException) { return; }  // dispatcher or window already gone
            }
        })
        { IsBackground = true, Name = "PocketPad.ShowSignal" };
        _waiter.Start();

        window.Show();
    }

    protected override void OnExit(System.Windows.ExitEventArgs e)
    {
        // Wake the waiter and let it leave WaitOne() before the handle goes
        // away. Disposing a wait handle that a thread is blocked on is
        // documented as undefined behaviour and does not reliably surface as
        // the ObjectDisposedException the loop is written to expect.
        _shuttingDown = true;
        try { _showEvent?.Set(); } catch (ObjectDisposedException) { /* already gone */ }
        _waiter?.Join(TimeSpan.FromMilliseconds(250));

        _showEvent?.Dispose();
        _instanceMutex?.Dispose();
        base.OnExit(e);
    }
}
