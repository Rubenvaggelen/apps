using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace TheOneMain.Windows;

/// <summary>
/// Keeps every non-primary display covered by a dedicated The One theme window.
/// The main display remains the interactive The One Windows dashboard.
/// Displays are re-evaluated while the app is running so hot-plugged monitors
/// automatically join (or leave) the The One desktop.
/// </summary>
public sealed class MultiMonitorThemeManager : IDisposable
{
    private readonly Window _mainWindow;
    private readonly Dictionary<string, Window> _themeWindows = new(StringComparer.OrdinalIgnoreCase);
    private readonly DispatcherTimer _monitorTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private bool _disposed;

    public MultiMonitorThemeManager(Window mainWindow)
    {
        _mainWindow = mainWindow;
        _monitorTimer.Tick += (_, _) => RefreshDisplays();
        _mainWindow.Closed += (_, _) => Dispose();
    }

    public void Start()
    {
        RefreshDisplays();
        _monitorTimer.Start();
    }

    private void RefreshDisplays()
    {
        if (_disposed) return;

        var secondary = EnumerateDisplays()
            .Where(display => !display.IsPrimary)
            .ToDictionary(display => display.DeviceName, StringComparer.OrdinalIgnoreCase);

        foreach (var removed in _themeWindows.Keys.Where(name => !secondary.ContainsKey(name)).ToArray())
        {
            var window = _themeWindows[removed];
            _themeWindows.Remove(removed);
            try { window.Close(); } catch { }
        }

        foreach (var pair in secondary)
        {
            if (_themeWindows.ContainsKey(pair.Key)) continue;

            var window = BuildThemeWindow(pair.Value);
            _themeWindows[pair.Key] = window;
            window.Closed += (_, _) => _themeWindows.Remove(pair.Key);
            window.Show();
        }
    }

    private static Window BuildThemeWindow(DisplayInfo screen)
    {
        var window = new Window
        {
            Title = "The One Theme",
            WindowStyle = WindowStyle.None,
            ResizeMode = ResizeMode.NoResize,
            ShowInTaskbar = false,
            Topmost = false,
            Background = new SolidColorBrush(Color.FromRgb(5, 7, 11)),
            AllowsTransparency = false,
            WindowStartupLocation = WindowStartupLocation.Manual
        };

        window.SourceInitialized += (_, _) =>
        {
            var hwnd = new WindowInteropHelper(window).Handle;
            var b = screen.Bounds;
            SetWindowPos(hwnd, HWND_TOP, b.Left, b.Top, b.Right - b.Left, b.Bottom - b.Top,
                SWP_NOACTIVATE | SWP_SHOWWINDOW);
        };

        var root = new Grid
        {
            Background = new RadialGradientBrush
            {
                GradientOrigin = new Point(0.5, 0.42),
                Center = new Point(0.5, 0.42),
                RadiusX = 0.75,
                RadiusY = 0.75,
                GradientStops =
                {
                    new GradientStop(Color.FromRgb(10, 32, 48), 0),
                    new GradientStop(Color.FromRgb(5, 13, 21), 0.48),
                    new GradientStop(Color.FromRgb(5, 7, 11), 1)
                }
            }
        };

        var center = new StackPanel
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };

        var logo = new Image
        {
            Source = new BitmapImage(new Uri("pack://application:,,,/Assets/the_one_logo.png", UriKind.Absolute)),
            Width = 300,
            Height = 300,
            Stretch = Stretch.Uniform,
            Opacity = 0.92,
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = Color.FromRgb(32, 184, 255),
                BlurRadius = 42,
                Opacity = 0.55,
                ShadowDepth = 0
            }
        };
        center.Children.Add(logo);

        center.Children.Add(new TextBlock
        {
            Text = "THE ONE",
            Foreground = new SolidColorBrush(Color.FromRgb(32, 184, 255)),
            FontSize = 42,
            FontWeight = FontWeights.Bold,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 18, 0, 0),
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                Color = Color.FromRgb(32, 184, 255),
                BlurRadius = 18,
                Opacity = 0.45,
                ShadowDepth = 0
            }
        });

        center.Children.Add(new TextBlock
        {
            Text = "THE ONE FAMILY",
            Foreground = new SolidColorBrush(Color.FromRgb(145, 164, 189)),
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            CharacterSpacing = 180,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 7, 0, 0)
        });

        root.Children.Add(center);

        root.Children.Add(new TextBlock
        {
            Text = "THE ONE WINDOWS",
            Foreground = new SolidColorBrush(Color.FromArgb(145, 32, 184, 255)),
            FontSize = 11,
            FontWeight = FontWeights.Bold,
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(0, 0, 26, 20)
        });

        window.Content = root;
        return window;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _monitorTimer.Stop();

        foreach (var window in _themeWindows.Values.ToArray())
        {
            try { window.Close(); } catch { }
        }
        _themeWindows.Clear();
    }

    private sealed record DisplayInfo(string DeviceName, RECT Bounds, bool IsPrimary);

    private static List<DisplayInfo> EnumerateDisplays()
    {
        var displays = new List<DisplayInfo>();
        EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, (monitor, _, _, _) =>
        {
            var info = new MONITORINFOEX { cbSize = Marshal.SizeOf<MONITORINFOEX>() };
            if (GetMonitorInfo(monitor, ref info))
                displays.Add(new DisplayInfo(info.szDevice, info.rcMonitor, (info.dwFlags & 1) != 0));
            return true;
        }, IntPtr.Zero);
        return displays;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct MONITORINFOEX
    {
        public int cbSize;
        public RECT rcMonitor;
        public RECT rcWork;
        public uint dwFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string szDevice;
    }

    private delegate bool MonitorEnumProc(IntPtr hMonitor, IntPtr hdcMonitor, IntPtr lprcMonitor, IntPtr dwData);

    [DllImport("user32.dll")]
    private static extern bool EnumDisplayMonitors(IntPtr hdc, IntPtr lprcClip, MonitorEnumProc callback, IntPtr dwData);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFOEX lpmi);

    private static readonly IntPtr HWND_TOP = IntPtr.Zero;
    private const uint SWP_NOACTIVATE = 0x0010;
    private const uint SWP_SHOWWINDOW = 0x0040;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(
        IntPtr hWnd, IntPtr hWndInsertAfter,
        int X, int Y, int cx, int cy, uint uFlags);
}
