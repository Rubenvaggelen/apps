using System.Windows;

namespace TheOneMain.Windows;

public partial class App : Application
{
    private MultiMonitorThemeManager? _multiMonitorTheme;
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        if (System.Array.Exists(
            e.Args,
            arg => string.Equals(
                arg,
                "--background-sync",
                System.StringComparison.OrdinalIgnoreCase)))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            UsbMusicCloudService.StartBackgroundSync();
            return;
        }

        if (System.Array.Exists(
            e.Args,
            arg => string.Equals(
                arg,
                "--hub-sync-once",
                System.StringComparison.OrdinalIgnoreCase)))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            try
            {
                UsbMusicCloudService.SyncHubInboxAsync().GetAwaiter().GetResult();
                Shutdown(0);
            }
            catch
            {
                Shutdown(2);
            }
            return;
        }

        if (System.Array.Exists(
            e.Args,
            arg => string.Equals(
                arg,
                "--repair-kids",
                System.StringComparison.OrdinalIgnoreCase)))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            try
            {
                Task.Run(KidsRepair.RunAsync).GetAwaiter().GetResult();
                Shutdown(0);
            }
            catch (Exception ex)
            {
                System.IO.Directory.CreateDirectory(@"C:\TheOne");
                System.IO.File.WriteAllText(@"C:\TheOne\kids-repair-error.txt", ex.ToString());
                Shutdown(2);
            }
            return;
        }

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
