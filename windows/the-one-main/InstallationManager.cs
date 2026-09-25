using System.Diagnostics;
using System.IO;

namespace TheOneMain.Windows;

public static class InstallationManager
{
    private const string FamilyFolder = "The One Family";
    private const string AppFolder = "The One Main";
    private const string ExeName = "TheOneMain.exe";

    public static string InstallDirectory =>
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Programs",
            FamilyFolder,
            AppFolder);

    public static string InstalledExe => Path.Combine(InstallDirectory, ExeName);

    public static bool EnsureInstalledAndRelaunchIfNeeded()
    {
        var currentExe = Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(currentExe) || !File.Exists(currentExe))
            return false;

        Directory.CreateDirectory(InstallDirectory);

        if (PathsEqual(currentExe, InstalledExe))
            return false;

        // Eerste run: zet één vaste kopie neer. Vanaf hier werken alle updates
        // rechtstreeks op dezelfde installatie; opnieuw installeren is niet nodig.
        File.Copy(currentExe, InstalledExe, overwrite: true);
        EnsureShortcuts();

        Process.Start(new ProcessStartInfo(InstalledExe)
        {
            UseShellExecute = true,
            WorkingDirectory = InstallDirectory
        });

        return true;
    }

    public static void EnsureShortcuts()
    {
        try
        {
            Directory.CreateDirectory(InstallDirectory);

            var startMenu = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "Microsoft",
                "Windows",
                "Start Menu",
                "Programs",
                FamilyFolder);
            Directory.CreateDirectory(startMenu);

            var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);

            CreateShortcut(
                Path.Combine(startMenu, "The One Main.lnk"),
                InstalledExe,
                InstallDirectory);

            CreateShortcut(
                Path.Combine(desktop, "The One Main.lnk"),
                InstalledExe,
                InstallDirectory);
        }
        catch
        {
            // Een koppeling is gemak; een fout hierin mag The One nooit blokkeren.
        }
    }

    private static void CreateShortcut(string shortcutPath, string targetPath, string workingDirectory)
    {
        var escapedShortcut = shortcutPath.Replace("'", "''");
        var escapedTarget = targetPath.Replace("'", "''");
        var escapedWork = workingDirectory.Replace("'", "''");

        var command =
            "$ws = New-Object -ComObject WScript.Shell; " +
            $"$s = $ws.CreateShortcut('{escapedShortcut}'); " +
            $"$s.TargetPath = '{escapedTarget}'; " +
            $"$s.WorkingDirectory = '{escapedWork}'; " +
            "$s.Description = 'The One Main - The One Family'; " +
            "$s.Save()";

        using var process = Process.Start(new ProcessStartInfo("powershell.exe")
        {
            Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command \"" +
                        command.Replace("\"", "\\\"") + "\"",
            UseShellExecute = false,
            CreateNoWindow = true
        });
        process?.WaitForExit(5000);
    }

    private static bool PathsEqual(string a, string b) =>
        string.Equals(
            Path.GetFullPath(a).TrimEnd(Path.DirectorySeparatorChar),
            Path.GetFullPath(b).TrimEnd(Path.DirectorySeparatorChar),
            StringComparison.OrdinalIgnoreCase);
}
