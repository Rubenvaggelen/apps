[Reading 182 lines from start (total: 182 lines, 0 remaining)]

using System.Collections;
using System.Net.Http.Headers;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace TheOneMain.Windows;

internal static class KidsRepair
{
    private const string Endpoint = "https://rubenvanaggelen.com/the-one-remote-api/music.php";
    private const string Root = @"C:\TheOne\SharedMedia\Music\Kids map";

    public static async Task<string> RunAsync()
    {
        var catalog = await UsbMusicCloudService.GetCatalogAsync(includeInactive: true);
        var hub = catalog.FirstOrDefault(x =>
            x.DeviceId.Equals("THEONE-HUB", StringComparison.OrdinalIgnoreCase) &&
            x.StickId.Equals("hub-primary", StringComparison.OrdinalIgnoreCase))
            ?? throw new InvalidOperationException("THEONE-HUB catalogus ontbreekt.");

        var tokenField = typeof(UsbMusicCloudService).GetField("_token",
            BindingFlags.NonPublic | BindingFlags.Static)
            ?? throw new InvalidOperationException("Sync-token niet beschikbaar.");
        var token = (string?)tokenField.GetValue(null) ?? "";
        if (string.IsNullOrWhiteSpace(token))
            throw new InvalidOperationException("Sync-token is leeg.");

        var files = Directory.EnumerateFiles(Root, "*", SearchOption.TopDirectoryOnly)
            .Where(IsAudio)
            .OrderBy(x => x, StringComparer.OrdinalIgnoreCase)
            .ToList();

        var local = new List<Dictionary<string, object?>>();
        foreach (var file in files)
        {
            await using var stream = File.OpenRead(file);
            var sha = Convert.ToHexString(await SHA256.HashDataAsync(stream)).ToLowerInvariant();
            var info = new FileInfo(file);
            local.Add(new Dictionary<string, object?>
            {
                ["path"] = "Kids map/" + info.Name,
                ["title"] = "",
                ["artist"] = "",
                ["album"] = "",
                ["size"] = info.Length,
                ["sha256"] = sha,
                ["modified"] = info.LastWriteTimeUtc.ToString("O")
            });
        }

        var manifest = new List<Dictionary<string, object?>>();
        foreach (var old in hub.Files)
        {
            var p = old.Path.Replace('\\', '/').Trim('/');
            if (p.StartsWith("Kids map/", StringComparison.OrdinalIgnoreCase))
                continue;
            manifest.Add(new Dictionary<string, object?>
            {
                ["path"] = p,
                ["title"] = old.Title,
                ["artist"] = old.Artist,
                ["album"] = old.Album,
                ["size"] = old.Size,
                ["sha256"] = old.Sha256,
                ["modified"] = old.Modified
            });
        }
        manifest.AddRange(local);

        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);

        async Task PostJson(string action, object body)
        {
            var json = JsonSerializer.Serialize(body);
            using var res = await http.PostAsync(
                Endpoint + "?action=" + action,
                new StringContent(json, Encoding.UTF8, "application/json"));
            var payload = await res.Content.ReadAsStringAsync();
            if (!res.IsSuccessStatusCode)
                throw new HttpRequestException(action + " " + (int)res.StatusCode + ": " + payload);
        }

        async Task PublishManifest()
        {
            const int batchSize = 100;
            var total = Math.Max(1, (manifest.Count + batchSize - 1) / batchSize);
            var syncId = "kidsrepair" + Guid.NewGuid().ToString("N");
            for (var index = 0; index < total; index++)
            {
                await PostJson("sync-batch", new
                {
                    device_id = "THEONE-HUB",
                    device_name = "THEONE-HUB",
                    stick_id = "hub-primary",
                    stick_name = string.IsNullOrWhiteSpace(hub.StickName) ? "Ruben music" : hub.StickName,
                    sync_id = syncId,
                    batch_index = index,
                    batch_total = total,
                    files = manifest.Skip(index * batchSize).Take(batchSize).ToList()
                });
            }
        }

        await PublishManifest();

        var refreshed = await UsbMusicCloudService.GetCatalogAsync(includeInactive: true);
        var refreshedHub = refreshed.First(x =>
            x.DeviceId.Equals("THEONE-HUB", StringComparison.OrdinalIgnoreCase) &&
            x.StickId.Equals("hub-primary", StringComparison.OrdinalIgnoreCase));
        var byPath = refreshedHub.Files.ToDictionary(
            x => x.Path.Replace('\\', '/').Trim('/'),
            x => x,
            StringComparer.OrdinalIgnoreCase);

        var uploaded = 0;
        foreach (var item in local)
        {
            var rel = (string)item["path"]!;
            var sha = (string)item["sha256"]!;
            if (byPath.TryGetValue(rel, out var remote) &&
                remote.Cached &&
                remote.Sha256.Equals(sha, StringComparison.OrdinalIgnoreCase))
                continue;

            var source = Path.Combine(Root, Path.GetFileName(rel));
            await PostJson("upload-start", new
            {
                device_id = "THEONE-HUB", stick_id = "hub-primary", path = rel, sha256 = sha
            });

            const int chunkSize = 32 * 1024;
            var buffer = new byte[chunkSize];
            long offset = 0;
            await using var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            while (true)
            {
                var read = await input.ReadAsync(buffer.AsMemory(0, buffer.Length));
                if (read <= 0) break;
                await PostJson("upload-chunk", new
                {
                    device_id = "THEONE-HUB",
                    stick_id = "hub-primary",
                    path = rel,
                    sha256 = sha,
                    offset,
                    data = Convert.ToBase64String(buffer, 0, read)
                });
                offset += read;
            }
            await PostJson("upload-finish", new
            {
                device_id = "THEONE-HUB", stick_id = "hub-primary", path = rel, sha256 = sha
            });
            uploaded++;
        }

        await PublishManifest();

        var finalCatalog = await UsbMusicCloudService.GetCatalogAsync(includeInactive: true);
        var finalHub = finalCatalog.First(x =>
            x.DeviceId.Equals("THEONE-HUB", StringComparison.OrdinalIgnoreCase) &&
            x.StickId.Equals("hub-primary", StringComparison.OrdinalIgnoreCase));
        var finalKids = finalHub.Files.Where(x =>
            x.Path.Replace('\\', '/').StartsWith("Kids map/", StringComparison.OrdinalIgnoreCase)).ToList();
        var cached = finalKids.Count(x => x.Cached);

        var result = $"Kids repair: local={local.Count}, catalog={finalKids.Count}, cached={cached}, uploaded={uploaded}";
        Directory.CreateDirectory(@"C:\TheOne");
        await File.WriteAllTextAsync(@"C:\TheOne\kids-repair-result.txt", result);
        return result;
    }

    private static bool IsAudio(string path)
    {
        var ext = Path.GetExtension(path);
        return new[] { ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac", ".wav", ".wma" }
            .Contains(ext, StringComparer.OrdinalIgnoreCase);
    }
}

[executed on device: Ruben (89001e2c-0797-4d3b-96e7-237cae9f9633)]