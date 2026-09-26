using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace TheOneMain.Windows;

public static class InstallationManager
{
    private const string FamilyFolder = "The One Family";
    private const string AppFolder = "The One Window";
    private const string LegacyAppFolder = "The One Main";
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

        // Eerste run: kopieer de HELE publish-map. WPF self-contained builds
        // bevatten naast TheOneMain.exe ook native runtimebestanden die nodig zijn
        // om de app daadwerkelijk te starten.
        var sourceDirectory = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
        CopyDirectory(sourceDirectory, InstallDirectory);
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
                Path.Combine(startMenu, "The One Windows.lnk"),
                InstalledExe,
                InstallDirectory);

            CreateShortcut(
                Path.Combine(desktop, "The One Windows.lnk"),
                InstalledExe,
                InstallDirectory);

            DeleteIfExists(Path.Combine(startMenu, "The One Window.lnk"));
            DeleteIfExists(Path.Combine(desktop, "The One Window.lnk"));
            DeleteIfExists(Path.Combine(startMenu, "The One Main.lnk"));
            DeleteIfExists(Path.Combine(desktop, "The One Main.lnk"));
            RegisterInstalledApp();
            CleanupLegacyInstall();
        }
        catch
        {
            // Een koppeling is gemak; een fout hierin mag The One nooit blokkeren.
        }
    }

    private static void CreateShortcut(string shortcutPath, string targetPath, string workingDirectory)
    {
        object? shell = null;
        object? shortcut = null;
        try
        {
            var shellType = Type.GetTypeFromProgID("WScript.Shell")
                            ?? throw new InvalidOperationException("Windows Script Host is niet beschikbaar.");
            shell = Activator.CreateInstance(shellType)
                    ?? throw new InvalidOperationException("Kan Windows Script Host niet starten.");

            dynamic ws = shell;
            shortcut = ws.CreateShortcut(shortcutPath);
            dynamic link = shortcut;
            link.TargetPath = targetPath;
            link.WorkingDirectory = workingDirectory;
            link.Description = "The One Windows - Part of The One Family";
            link.IconLocation = $"{targetPath},0";
            link.Save();
        }
        finally
        {
            if (shortcut != null && Marshal.IsComObject(shortcut))
                Marshal.FinalReleaseComObject(shortcut);
            if (shell != null && Marshal.IsComObject(shell))
                Marshal.FinalReleaseComObject(shell);
        }
    }

    private static void RegisterInstalledApp()
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Uninstall\The One Window");
            key?.SetValue("DisplayName", "The One Windows");
            key?.SetValue("Publisher", "The One Family");
            key?.SetValue("DisplayIcon", InstalledExe);
            key?.SetValue("InstallLocation", InstallDirectory);
            key?.SetValue("DisplayVersion", BuildInfo.Version.ToString());
            key?.SetValue("NoModify", 1, RegistryValueKind.DWord);
            key?.SetValue("NoRepair", 1, RegistryValueKind.DWord);
        }
        catch { }
    }

    private static void DeleteIfExists(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    private static void CleanupLegacyInstall()
    {
        try
        {
            var legacy = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Programs",
                FamilyFolder,
                LegacyAppFolder);

            var current = Environment.ProcessPath ?? "";
            if (Directory.Exists(legacy) &&
                !current.StartsWith(legacy, StringComparison.OrdinalIgnoreCase))
                Directory.Delete(legacy, recursive: true);
        }
        catch { }
    }

    private static void CopyDirectory(string sourceDirectory, string targetDirectory)
    {
        Directory.CreateDirectory(targetDirectory);

        foreach (var sourceFile in Directory.EnumerateFiles(sourceDirectory, "*", SearchOption.AllDirectories))
        {
            var relative = Path.GetRelativePath(sourceDirectory, sourceFile);
            var targetFile = Path.Combine(targetDirectory, relative);
            var targetParent = Path.GetDirectoryName(targetFile);
            if (!string.IsNullOrWhiteSpace(targetParent))
                Directory.CreateDirectory(targetParent);

            File.Copy(sourceFile, targetFile, overwrite: true);
        }
    }

    private static bool PathsEqual(string a, string b) =>
        string.Equals(
            Path.GetFullPath(a).TrimEnd(Path.DirectorySeparatorChar),
            Path.GetFullPath(b).TrimEnd(Path.DirectorySeparatorChar),
            StringComparison.OrdinalIgnoreCase);
}
