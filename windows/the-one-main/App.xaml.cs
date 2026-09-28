using System.Windows;

namespace TheOneMain.Windows;

public partial class App : Application
{
    private MultiMonitorThemeManager? _multiMonitorTheme;
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // De eerste keer mag The One vanuit Downloads/een ZIP gestart worden.
        // Daarna verhuist de app zichzelf naar een vaste gebruikersmap en maakt
        // hij Startmenu- en bureaubladkoppelingen. Updates blijven daarna in-place.
        if (InstallationManager.EnsureInstalledAndRelaunchIfNeeded())
        {
            Shutdown();
            return;
        }

        InstallationManager.EnsureShortcuts();

        var window = new MainWindow();
        MainWindow = window;
        window.Show();

        // Geef elk extra aangesloten scherm automatisch de The One-uitstraling.
        // Het primaire scherm blijft het interactieve dashboard.
        _multiMonitorTheme = new MultiMonitorThemeManager(window);
        _multiMonitorTheme.Start();

        WindowsUpdateService.ShowCompletedUpdateIfNeeded(window);

        // Windows heeft een volledig eigen updatekanaal. Dit raakt The One
        // Android en The One Car niet.
        _ = WindowsUpdateService.CheckForUpdateAsync(window, silentIfCurrent: true);
    }
}
