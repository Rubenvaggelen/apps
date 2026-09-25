using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Windows;

namespace TheOneMain.Windows;

public static class WindowsUpdateService
{
    private const string ReleasesUrl = "https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=50";
    private const string TagPrefix = "windows-v";
    private const string AssetName = "The-One-Main-Windows.zip";
    private static readonly HttpClient Http = CreateClient();

    private sealed record UpdateInfo(int Version, string DownloadUrl);

    public static async Task CheckForUpdateAsync(Window owner, bool silentIfCurrent)
    {
        try
        {
            var update = await FindLatestAsync();
            if (update == null || update.Version <= BuildInfo.Version)
            {
                if (!silentIfCurrent)
                    MessageBox.Show($"Je hebt de nieuwste Windows-versie.\nBuild {BuildInfo.Version}.", "The One Update");
                return;
            }

            var answer = MessageBox.Show(
                $"Nieuwe The One Main Windows-update beschikbaar.\n\n" +
                $"Huidig: build {BuildInfo.Version}\nNieuw: build {update.Version}\n\n" +
                "Nu bijwerken? De app start daarna automatisch opnieuw.",
                "The One Update",
                MessageBoxButton.YesNo,
                MessageBoxImage.Information);

            if (answer != MessageBoxResult.Yes) return;
            await DownloadAndInstallAsync(owner, update);
        }
        catch (Exception ex)
        {
            if (!silentIfCurrent)
                MessageBox.Show("Updatecontrole mislukt: " + ex.Message, "The One Update");
        }
    }

    private static async Task<UpdateInfo?> FindLatestAsync()
    {
        using var response = await Http.GetAsync(ReleasesUrl);
        response.EnsureSuccessStatusCode();
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        UpdateInfo? best = null;
        foreach (var release in doc.RootElement.EnumerateArray())
        {
            if (release.TryGetProperty("draft", out var draft) && draft.GetBoolean()) continue;
            if (!release.TryGetProperty("prerelease", out var pre) || !pre.GetBoolean()) continue;

            var tag = release.GetProperty("tag_name").GetString().orEmpty();
            if (!tag.StartsWith(TagPrefix, StringComparison.OrdinalIgnoreCase)) continue;
            if (!int.TryParse(tag[TagPrefix.Length..], out var version)) continue;

            string? download = null;
            if (release.TryGetProperty("assets", out var assets))
            {
                foreach (var asset in assets.EnumerateArray())
                {
                    if (!string.Equals(asset.GetProperty("name").GetString(), AssetName, StringComparison.OrdinalIgnoreCase))
                        continue;
                    download = asset.GetProperty("browser_download_url").GetString();
                    break;
                }
            }

            if (string.IsNullOrWhiteSpace(download)) continue;
            if (best == null || version > best.Version) best = new UpdateInfo(version, download);
        }
        return best;
    }

    private static async Task DownloadAndInstallAsync(Window owner, UpdateInfo update)
    {
        var exePath = Environment.ProcessPath ?? throw new InvalidOperationException("App-pad niet gevonden.");
        var installDir = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
        EnsureWritable(installDir);

        var root = Path.Combine(Path.GetTempPath(), "TheOneUpdate", update.Version.ToString());
        if (Directory.Exists(root)) Directory.Delete(root, true);
        Directory.CreateDirectory(root);

        var zipPath = Path.Combine(root, AssetName);
        var unpackDir = Path.Combine(root, "new");
        Directory.CreateDirectory(unpackDir);

        owner.IsEnabled = false;
        owner.Title = $"The One — update {update.Version} downloaden…";
        try
        {
            using (var response = await Http.GetAsync(update.DownloadUrl, HttpCompletionOption.ResponseHeadersRead))
            {
                response.EnsureSuccessStatusCode();
                await using var source = await response.Content.ReadAsStreamAsync();
                await using var target = File.Create(zipPath);
                await source.CopyToAsync(target);
            }

            ZipFile.ExtractToDirectory(zipPath, unpackDir, overwriteFiles: true);
            var newExe = Directory.EnumerateFiles(unpackDir, "TheOneMain.exe", SearchOption.AllDirectories).FirstOrDefault()
                         ?? throw new InvalidOperationException("TheOneMain.exe ontbreekt in de update.");

            var sourceDir = Path.GetDirectoryName(newExe)!;
            var updaterPath = Path.Combine(root, "apply-update.ps1");
            var script = BuildUpdaterScript();
            await File.WriteAllTextAsync(updaterPath, script);

            var args =
                $"-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"{updaterPath}\" " +
                $"-ProcessId {Environment.ProcessId} -Source \"{sourceDir}\" -Target \"{installDir}\" " +
                $"-ExeName \"{Path.GetFileName(exePath)}\" -Cleanup \"{root}\"";

            Process.Start(new ProcessStartInfo("powershell.exe", args)
            {
                UseShellExecute = true,
                CreateNoWindow = true,
                WorkingDirectory = installDir
            });

            AppStore.AddNotification($"Windows-update build {update.Version} wordt geïnstalleerd.");
            Application.Current.Shutdown();
        }
        catch
        {
            owner.IsEnabled = true;
            owner.Title = "The One";
            throw;
        }
    }

    private static string BuildUpdaterScript() => """
param(
  [int]$ProcessId,
  [string]$Source,
  [string]$Target,
  [string]$ExeName,
  [string]$Cleanup
)
$ErrorActionPreference = 'Stop'
try {
  Wait-Process -Id $ProcessId -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 700
  Copy-Item -Path (Join-Path $Source '*') -Destination $Target -Recurse -Force
  Start-Process -FilePath (Join-Path $Target $ExeName) -WorkingDirectory $Target
}
finally {
  Start-Sleep -Seconds 1
  Remove-Item -LiteralPath $Cleanup -Recurse -Force -ErrorAction SilentlyContinue
}
""";

    private static void EnsureWritable(string directory)
    {
        var probe = Path.Combine(directory, ".theone-update-write-test");
        try
        {
            File.WriteAllText(probe, "ok");
            File.Delete(probe);
        }
        catch
        {
            throw new InvalidOperationException(
                "The One kan zichzelf in deze map niet bijwerken. Verplaats de app naar een normale map binnen je gebruikersaccount.");
        }
    }

    private static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromMinutes(3) };
        client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("TheOneMainWindows", "1.0"));
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        return client;
    }

    private static string orEmpty(this string? value) => value ?? "";
}
