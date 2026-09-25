using System.Windows;

namespace TheOneMain.Windows;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var window = new MainWindow();
        MainWindow = window;
        window.Show();

        // Windows heeft een volledig eigen updatekanaal. Dit raakt The One
        // Android en The One Car niet.
        _ = WindowsUpdateService.CheckForUpdateAsync(window, silentIfCurrent: true);
    }
}
