using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using PocketPad.Core;
using QRCoder;
using Application = System.Windows.Application;
using Brush = System.Windows.Media.Brush;
using Clipboard = System.Windows.Clipboard;
using MessageBox = System.Windows.MessageBox;
using Point = System.Windows.Point;

namespace PocketPad.App;

public partial class MainWindow : Window
{
    private LinkSession? _session;
    private System.Windows.Forms.NotifyIcon? _tray;
    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly List<int> _latHistory = new();
    private readonly Dictionary<int, (string Name, int? Ms)> _phones = new();
    private DateTime _connectedAtUtc;
    private bool _reallyExit;
    private bool _shownTrayHint;

    /// <summary>
    /// Startup hit a fatal port clash, so <c>Shutdown()</c> is already queued and
    /// this window must never be shown. Read by <see cref="App"/> on startup,
    /// because a queued shutdown does not stop the caller from calling Show().
    /// </summary>
    public bool StartupFailed { get; private set; }

    public MainWindow()
    {
        InitializeComponent();
        SetupTray();
        StartPulse();
        _tick.Tick += (_, _) => RefreshStats();
        StartSession();
    }

    // ================= session =================

    private void StartSession()
    {
        try
        {
            _session = new LinkSession();
        }
        catch (LinkSession.DriverMissingException)
        {
            ShowView(DriverView);
            return;
        }
        catch (System.Net.Sockets.SocketException)
        {
            // Ports taken — almost always a second copy that slipped past the
            // mutex (e.g. different user session). Say so instead of dying.
            MessageBox.Show(
                "PocketPad seems to be running already — check the tray icons " +
                "next to the clock.\n\nIf it isn't, another program is using " +
                "network ports 46821/46822. Close it and reopen PocketPad.",
                "PocketPad", MessageBoxButton.OK, MessageBoxImage.Information);
            // Shutdown() only posts to the dispatcher, so control returns
            // through this constructor to OnStartup, which would flash the
            // window before the shutdown lands. Flag that for the caller and
            // drop the tray icon here — ExitApp's cleanup never runs on this
            // path, leaving a ghost icon until the user hovers over it.
            _reallyExit = true;
            StartupFailed = true;
            DisposeTray();
            Application.Current.Shutdown();
            return;
        }

        var ip = LinkSession.PrimaryIPv4();
        AddrText.Text = ip.ToString();
        CodeText.Text = _session.Token;
        var qr = MakeQr(_session.PairingUri(ip));
        QrImage.Source = qr;
        QrImageSmall.Source = qr;

        _session.PhoneConnected += (player, name, _) => Dispatcher.Invoke(() =>
        {
            bool first = _phones.Count == 0;
            _phones[player] = (name, null);
            if (first)
            {
                _connectedAtUtc = DateTime.UtcNow;
                _latHistory.Clear();
                MsText.Text = "—";
                _tick.Start();
            }
            RefreshPhones();
            ShowView(ConnectedView);
            _tray!.Text = $"PocketPad — {_phones.Count} connected";
            _tray.ShowBalloonTip(1800, "PocketPad",
                $"{name} connected as player {player + 1}. Start your game!",
                System.Windows.Forms.ToolTipIcon.Info);
        });

        _session.PhoneDisconnected += (player, remaining) => Dispatcher.Invoke(() =>
        {
            _phones.Remove(player);
            if (remaining <= 0 || _phones.Count == 0)
            {
                _tick.Stop();
                ShowView(WaitingView);
                _tray!.Text = "PocketPad — waiting for phone";
            }
            else
            {
                RefreshPhones();
                _tray!.Text = $"PocketPad — {_phones.Count} connected";
            }
        });

        _session.LatencyReported += (player, ms) => Dispatcher.Invoke(() =>
        {
            if (_phones.TryGetValue(player, out var p))
                _phones[player] = (p.Name, ms);
            RefreshPhones();
            OnLatency(ms);
        });

        ShowView(WaitingView);
        _ = RunSessionAsync(_session);
    }

    private async Task RunSessionAsync(LinkSession session)
    {
        try
        {
            await session.RunAsync();
        }
        catch (Exception e)
        {
            // A server crash (port stolen, socket teardown) shouldn't be silent.
            Dispatcher.Invoke(() =>
                MessageBox.Show(this,
                    "PocketPad hit a network problem and stopped.\n\n" + e.Message +
                    "\n\nClose and reopen the app to restart it.",
                    "PocketPad", MessageBoxButton.OK, MessageBoxImage.Warning));
        }
    }

    // ================= UI plumbing =================

    private void RefreshPhones()
    {
        PhoneName.Text = _phones.Count == 1
            ? $"{_phones.Values.First().Name} connected"
            : $"{_phones.Count} phones connected";

        PhonesList.Children.Clear();
        foreach (var (player, info) in _phones.OrderBy(kv => kv.Key))
        {
            var row = new TextBlock
            {
                FontSize = 12.5,
                Foreground = (Brush)FindResource("InkSoft"),
                Margin = new Thickness(0, 0, 0, 3),
                Text = $"P{player + 1}   {info.Name}" +
                       (info.Ms is int ms ? $"   ·   {ms} ms" : ""),
            };
            PhonesList.Children.Add(row);
        }

        bool full = _phones.Count >= 4;
        AddPhonePanel.Visibility = full ? Visibility.Collapsed : Visibility.Visible;
        AddPhoneCaption.Text = full
            ? "All 4 player slots in use"
            : "Add another phone — scan\n(up to 4 players)";
    }

    private void ShowView(UIElement view)
    {
        WaitingView.Visibility = ReferenceEquals(view, WaitingView) ? Visibility.Visible : Visibility.Collapsed;
        ConnectedView.Visibility = ReferenceEquals(view, ConnectedView) ? Visibility.Visible : Visibility.Collapsed;
        DriverView.Visibility = ReferenceEquals(view, DriverView) ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnLatency(int ms)
    {
        MsText.Text = ms.ToString();
        MsQual.Text = ms switch
        {
            < 10 => " ms — excellent",
            < 20 => " ms — great",
            < 40 => " ms — okay",
            _    => " ms — laggy, check Wi-Fi",
        };
        var brush = ms < 20 ? (Brush)FindResource("Good") : (Brush)FindResource("AddrColor");
        MsText.Foreground = brush;
        SparkLine.Stroke = brush;

        _latHistory.Add(ms);
        if (_latHistory.Count > 60) _latHistory.RemoveAt(0);
        RedrawSpark();
    }

    private void RedrawSpark()
    {
        if (_latHistory.Count < 2) { SparkLine.Points = new PointCollection(); return; }
        double w = SparkCanvas.Width, h = SparkCanvas.Height;
        int max = Math.Max(20, _latHistory.Max());
        var pts = new PointCollection();
        for (int i = 0; i < _latHistory.Count; i++)
        {
            double x = w * i / Math.Max(1, _latHistory.Count - 1);
            double y = h - 4 - (h - 8) * _latHistory[i] / max;
            pts.Add(new Point(x, y));
        }
        SparkLine.Points = pts;
    }

    private void RefreshStats()
    {
        if (_session is null) return;
        StatInputs.Text = _session.PacketsReceived.ToString("N0");
        var mins = (int)(DateTime.UtcNow - _connectedAtUtc).TotalMinutes;
        StatTime.Text = mins < 1 ? "just now" : $"{mins} min";
    }

    private void StartPulse()
    {
        var anim = new DoubleAnimation(1.0, 0.25, TimeSpan.FromSeconds(0.9))
        {
            AutoReverse = true,
            RepeatBehavior = RepeatBehavior.Forever,
            EasingFunction = new SineEase(),
        };
        PulseDot.BeginAnimation(OpacityProperty, anim);
    }

    private static BitmapImage MakeQr(string payload)
    {
        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(10, drawQuietZones: false);
        var img = new BitmapImage();
        using var ms = new MemoryStream(png);
        img.BeginInit();
        img.CacheOption = BitmapCacheOption.OnLoad;
        img.StreamSource = ms;
        img.EndInit();
        img.Freeze();
        return img;
    }

    // ================= tray =================

    private void SetupTray()
    {
        _tray = new System.Windows.Forms.NotifyIcon
        {
            Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!),
            Visible = true,
            Text = "PocketPad — waiting for phone",
        };
        _tray.DoubleClick += (_, _) => RestoreFromTray();

        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Open PocketPad", null, (_, _) => RestoreFromTray());
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        menu.Items.Add("Quit completely", null, (_, _) => ExitApp());
        _tray.ContextMenuStrip = menu;
    }

    private void RestoreFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    /// <summary>The user double-clicked the desktop icon while we're running.</summary>
    public void ShowFromSecondLaunch() => RestoreFromTray();

    /// <summary>Hide then release the tray icon; safe to call more than once.</summary>
    private void DisposeTray()
    {
        if (_tray is null) return;
        _tray.Visible = false; // hide before disposing, or the icon lingers until hover
        _tray.Dispose();
        _tray = null;
    }

    private void ExitApp()
    {
        _reallyExit = true;
        DisposeTray();
        _session?.Dispose();
        Application.Current.Shutdown();
    }

    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        if (!_reallyExit)
        {
            // Closing hides to tray so the controller keeps working behind the game.
            e.Cancel = true;
            Hide();
            if (!_shownTrayHint)
            {
                _shownTrayHint = true;
                _tray!.ShowBalloonTip(2500, "PocketPad is still running",
                    "Your controller stays connected. Right-click the tray icon to quit completely.",
                    System.Windows.Forms.ToolTipIcon.Info);
            }
        }
        base.OnClosing(e);
    }

    // ================= button handlers =================

    private void OnMinimize(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    private void OnCloseToTray(object sender, RoutedEventArgs e) => Close();
    private void OnCopyAddr(object sender, RoutedEventArgs e) => Clipboard.SetText(AddrText.Text);
    private void OnCopyCode(object sender, RoutedEventArgs e) => Clipboard.SetText(CodeText.Text);
    private void OnDisconnectPhone(object sender, RoutedEventArgs e) => _session?.DisconnectPhones();

    private void OnGetDriver(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo("https://github.com/nefarius/ViGEmBus/releases/latest")
        { UseShellExecute = true });

    private void OnHelp(object sender, RoutedEventArgs e)
    {
        // The manual ships beside the exe; open it in the default browser.
        var path = Path.Combine(AppContext.BaseDirectory, "manual.html");
        if (File.Exists(path))
            Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
        else
            MessageBox.Show(this,
                "The manual file (manual.html) is missing from the PocketPad folder.\n" +
                "Reinstall PocketPad to restore it.",
                "PocketPad", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void OnRetryDriver(object sender, RoutedEventArgs e)
    {
        if (_session is null) StartSession();
    }
}
