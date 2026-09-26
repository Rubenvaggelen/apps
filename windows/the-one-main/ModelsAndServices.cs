using Microsoft.Win32;
using System.Diagnostics;
using System.IO;
using System.Text.Json;

namespace TheOneMain.Windows;

public sealed class SettingsData
{
    public bool AutoStart { get; set; } = true;
    public bool StartMaximized { get; set; } = true;
    public HashSet<string> HiddenTiles { get; set; } = new();
    public List<CustomShortcut> CustomApps { get; set; } = new();
    public string RutuCompanyPath { get; set; } = "";
}

public sealed class CustomShortcut
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Label { get; set; } = "";
    public string ExePath { get; set; } = "";
    public string Url { get; set; } = "";
}

public sealed class ShoppingItem
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Text { get; set; } = "";
    public bool Done { get; set; }
}

public sealed class FinanceData
{
    public decimal StartAmount { get; set; }
    public decimal WarningThreshold { get; set; } = 100m;
    public List<FinanceTransaction> Transactions { get; set; } = new();
}

public sealed class FinanceTransaction
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public DateTime When { get; set; } = DateTime.Now;
    public string Description { get; set; } = "";
    public decimal Amount { get; set; }
}

public sealed class ParkingData
{
    public List<string> Addresses { get; set; } = new();
    public DateTime? EndTime { get; set; }
    public bool EndTimeAlertShown { get; set; }
}

public sealed class FitnessEntry
{
    public DateTime When { get; set; } = DateTime.Now;
    public string Activity { get; set; } = "";
    public int Minutes { get; set; }
}

public sealed class OneNotification
{
    public DateTime When { get; set; } = DateTime.Now;
    public string Text { get; set; } = "";
}

public static class AppStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    public static string BaseDirectory { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TheOne", "Windows");

    static AppStore() => Directory.CreateDirectory(BaseDirectory);

    public static T Load<T>(string file) where T : new()
    {
        try
        {
            var path = Path.Combine(BaseDirectory, file);
            if (!File.Exists(path)) return new T();
            return JsonSerializer.Deserialize<T>(File.ReadAllText(path), JsonOptions) ?? new T();
        }
        catch { return new T(); }
    }

    public static void Save<T>(string file, T value)
    {
        Directory.CreateDirectory(BaseDirectory);
        File.WriteAllText(Path.Combine(BaseDirectory, file), JsonSerializer.Serialize(value, JsonOptions));
    }

    public static void AddNotification(string text)
    {
        var list = Load<List<OneNotification>>("notifications.json");
        list.Insert(0, new OneNotification { Text = text, When = DateTime.Now });
        if (list.Count > 100) list.RemoveRange(100, list.Count - 100);
        Save("notifications.json", list);
    }
}

public static class StartupManager
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "The One Window";
    private const string LegacyValueName = "The One Main";

    public static void SetEnabled(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true) ??
                            Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
            if (enabled)
            {
                var exe = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule?.FileName;
                if (!string.IsNullOrWhiteSpace(exe)) key?.SetValue(ValueName, $"\"{exe}\"");
            }
            else key?.DeleteValue(ValueName, false);
            key?.DeleteValue(LegacyValueName, false);
        }
        catch { }
    }
}

public static class BrowserLauncher
{
    public static string? FindChrome()
    {
        string[] candidates =
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Google", "Chrome", "Application", "chrome.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Google", "Chrome", "Application", "chrome.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Google", "Chrome", "Application", "chrome.exe")
        };
        return candidates.FirstOrDefault(File.Exists);
    }

    public static void OpenChrome(string? url = null)
    {
        try
        {
            var chrome = FindChrome();
            if (chrome != null)
            {
                var args = string.IsNullOrWhiteSpace(url) ? "" : $"\"{url}\"";
                Process.Start(new ProcessStartInfo(chrome, args) { UseShellExecute = true });
                return;
            }

            if (!string.IsNullOrWhiteSpace(url))
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            else
                Process.Start(new ProcessStartInfo("https://www.google.com/") { UseShellExecute = true });
        }
        catch { }
    }

    public static void OpenSpotifySearch(string query)
    {
        if (string.IsNullOrWhiteSpace(query)) return;

        var encoded = Uri.EscapeDataString(query.Trim());

        try
        {
            Process.Start(new ProcessStartInfo("spotify:search:" + encoded)
            {
                UseShellExecute = true
            });
            return;
        }
        catch
        {
            // Spotify desktop app is niet geïnstalleerd of niet geregistreerd.
        }

        try
        {
            Process.Start(new ProcessStartInfo("https://open.spotify.com/search/" + encoded)
            {
                UseShellExecute = true
            });
        }
        catch { }
    }

    public static void OpenProgram(string path)
    {
        try { Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }); } catch { }
    }

    public static void OpenFolder(Environment.SpecialFolder folder)
    {
        try { Process.Start(new ProcessStartInfo("explorer.exe", $"\"{Environment.GetFolderPath(folder)}\"") { UseShellExecute = true }); } catch { }
    }
}
