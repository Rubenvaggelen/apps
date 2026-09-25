using Microsoft.Win32;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Media;

namespace TheOneMain.Windows;

public sealed class RutuCompanyInstall
{
    public bool Found { get; init; }
    public string LauncherPath { get; init; } = "";
    public string ExecutablePath { get; init; } = "";
    public string Version { get; init; } = "";
    public bool VersionKnown { get; init; }
    public bool IsLatestKnown { get; init; }
    public bool IsOutdated { get; init; }
    public ImageSource? Icon { get; init; }
}

public static class RutuCompanyAppService
{
    // Laatste Windows Company Build die binnen het Rutu-project gepubliceerd is.
    public const string LatestKnownVersion = "0.4.30";

    public static RutuCompanyInstall Detect()
    {
        try
        {
            var launcher = FindShortcut()
                ?? FindFromUninstallRegistry()
                ?? FindPortableExecutable();

            if (string.IsNullOrWhiteSpace(launcher))
                return new RutuCompanyInstall();

            var executable = ResolveExecutable(launcher);
            var version = ReadVersion(executable);

            var compare = CompareVersions(version, LatestKnownVersion);
            var iconPath = File.Exists(executable) ? executable : launcher;

            return new RutuCompanyInstall
            {
                Found = true,
                LauncherPath = launcher,
                ExecutablePath = executable,
                Version = version,
                VersionKnown = !string.IsNullOrWhiteSpace(version),
                IsLatestKnown = compare == 0,
                IsOutdated = compare < 0 && !string.IsNullOrWhiteSpace(version),
                Icon = CustomTileIconService.TryGetWindowsIcon(iconPath)
            };
        }
        catch
        {
            return new RutuCompanyInstall();
        }
    }

    public static void Open(RutuCompanyInstall install)
    {
        if (!install.Found || string.IsNullOrWhiteSpace(install.LauncherPath))
            return;

        try
        {
            Process.Start(new ProcessStartInfo(install.LauncherPath)
            {
                UseShellExecute = true
            });
        }
        catch { }
    }

    private static string? FindShortcut()
    {
        var roots = new[]
        {
            Environment.GetFolderPath(Environment.SpecialFolder.Programs),
            Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms),
            Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory)
        };

        foreach (var root in roots.Where(Directory.Exists))
        {
            IEnumerable<string> files;
            try
            {
                files = Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories);
            }
            catch
            {
                continue;
            }

            var best = files
                .Where(p => p.EndsWith(".lnk", StringComparison.OrdinalIgnoreCase) ||
                            p.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                .Select(p => new { Path = p, Score = ScoreName(Path.GetFileNameWithoutExtension(p)) })
                .Where(x => x.Score > 0)
                .OrderByDescending(x => x.Score)
                .FirstOrDefault();

            if (best != null)
                return best.Path;
        }

        return null;
    }

    private static string? FindFromUninstallRegistry()
    {
        var hives = new[]
        {
            (RegistryHive.CurrentUser, RegistryView.Default),
            (RegistryHive.LocalMachine, RegistryView.Registry64),
            (RegistryHive.LocalMachine, RegistryView.Registry32)
        };

        foreach (var (hive, view) in hives)
        {
            try
            {
                using var baseKey = RegistryKey.OpenBaseKey(hive, view);
                using var uninstall = baseKey.OpenSubKey(
                    @"Software\Microsoft\Windows\CurrentVersion\Uninstall");

                if (uninstall == null) continue;

                foreach (var subName in uninstall.GetSubKeyNames())
                {
                    using var sub = uninstall.OpenSubKey(subName);
                    var displayName = sub?.GetValue("DisplayName")?.ToString() ?? "";
                    if (ScoreName(displayName) <= 0) continue;

                    var icon = CleanPath(sub?.GetValue("DisplayIcon")?.ToString());
                    if (!string.IsNullOrWhiteSpace(icon) && File.Exists(icon))
                        return icon;

                    var installLocation = CleanPath(sub?.GetValue("InstallLocation")?.ToString());
                    var exe = FindRutuExeInDirectory(installLocation);
                    if (exe != null) return exe;
                }
            }
            catch { }
        }

        return null;
    }

    private static string? FindPortableExecutable()
    {
        var likelyRoots = new[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs"),
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86)
        };

        foreach (var root in likelyRoots.Where(Directory.Exists))
        {
            try
            {
                foreach (var dir in Directory.EnumerateDirectories(root, "*", SearchOption.TopDirectoryOnly))
                {
                    if (ScoreName(Path.GetFileName(dir)) <= 0) continue;
                    var exe = FindRutuExeInDirectory(dir);
                    if (exe != null) return exe;
                }
            }
            catch { }
        }

        return null;
    }

    private static string? FindRutuExeInDirectory(string? directory)
    {
        if (string.IsNullOrWhiteSpace(directory) || !Directory.Exists(directory))
            return null;

        try
        {
            return Directory.EnumerateFiles(directory, "*.exe", SearchOption.AllDirectories)
                .Select(p => new { Path = p, Score = ScoreName(Path.GetFileNameWithoutExtension(p)) })
                .OrderByDescending(x => x.Score)
                .ThenBy(x => x.Path.Length)
                .FirstOrDefault(x => x.Score > 0)?.Path;
        }
        catch
        {
            return null;
        }
    }

    private static string ResolveExecutable(string launcher)
    {
        if (!launcher.EndsWith(".lnk", StringComparison.OrdinalIgnoreCase))
            return launcher;

        object? shell = null;
        object? shortcut = null;
        try
        {
            var type = Type.GetTypeFromProgID("WScript.Shell");
            if (type == null) return launcher;

            shell = Activator.CreateInstance(type);
            if (shell == null) return launcher;

            dynamic ws = shell;
            shortcut = ws.CreateShortcut(launcher);
            dynamic link = shortcut;
            var target = (string?)link.TargetPath;
            return !string.IsNullOrWhiteSpace(target) && File.Exists(target)
                ? target
                : launcher;
        }
        catch
        {
            return launcher;
        }
        finally
        {
            if (shortcut != null && Marshal.IsComObject(shortcut))
                Marshal.FinalReleaseComObject(shortcut);
            if (shell != null && Marshal.IsComObject(shell))
                Marshal.FinalReleaseComObject(shell);
        }
    }

    private static string ReadVersion(string executable)
    {
        try
        {
            if (!File.Exists(executable) ||
                !executable.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                return "";

            var info = FileVersionInfo.GetVersionInfo(executable);
            var raw = info.ProductVersion.IfBlank(info.FileVersion.IfBlank(""));
            if (string.IsNullOrWhiteSpace(raw)) return "";

            var numeric = new string(raw
                .TakeWhile(c => char.IsDigit(c) || c == '.')
                .ToArray());

            return numeric.Trim('.');
        }
        catch
        {
            return "";
        }
    }

    private static int ScoreName(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return 0;
        var name = value.ToLowerInvariant();

        if (!name.Contains("rutu")) return 0;

        var score = 10;
        if (name.Contains("bbq")) score += 4;
        if (name.Contains("bedrijf")) score += 8;
        if (name.Contains("business")) score += 8;
        if (name.Contains("company")) score += 7;
        if (name.Contains("klant") || name.Contains("customer")) score -= 8;
        return score;
    }

    private static string CleanPath(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "";
        var value = raw.Trim().Trim('"');

        var comma = value.LastIndexOf(',');
        if (comma > 2)
        {
            var tail = value[(comma + 1)..].Trim();
            if (int.TryParse(tail, out _))
                value = value[..comma].Trim().Trim('"');
        }

        return Environment.ExpandEnvironmentVariables(value);
    }

    private static int CompareVersions(string installed, string latest)
    {
        if (string.IsNullOrWhiteSpace(installed)) return int.MinValue;

        var a = ParseVersion(installed);
        var b = ParseVersion(latest);
        var count = Math.Max(a.Length, b.Length);

        for (var i = 0; i < count; i++)
        {
            var av = i < a.Length ? a[i] : 0;
            var bv = i < b.Length ? b[i] : 0;
            if (av != bv) return av.CompareTo(bv);
        }

        return 0;
    }

    private static int[] ParseVersion(string value) =>
        value.Split('.', StringSplitOptions.RemoveEmptyEntries)
            .Select(x => int.TryParse(x, out var n) ? n : 0)
            .ToArray();
}
