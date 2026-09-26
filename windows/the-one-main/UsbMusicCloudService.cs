using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace TheOneMain.Windows;

public sealed class CloudUsbMusicFile
{
    [JsonPropertyName("path")] public string Path { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("folder")] public string Folder { get; set; } = "";
    [JsonPropertyName("size")] public long Size { get; set; }
    [JsonPropertyName("sha256")] public string Sha256 { get; set; } = "";
    [JsonPropertyName("modified")] public string Modified { get; set; } = "";
    [JsonPropertyName("cached")] public bool Cached { get; set; }
    [JsonIgnore] public string DeviceId { get; set; } = "";
    [JsonIgnore] public string StickId { get; set; } = "";
}

public sealed class CloudUsbMusicStick
{
    [JsonPropertyName("device_id")] public string DeviceId { get; set; } = "";
    [JsonPropertyName("device_name")] public string DeviceName { get; set; } = "";
    [JsonPropertyName("stick_id")] public string StickId { get; set; } = "";
    [JsonPropertyName("stick_name")] public string StickName { get; set; } = "";
    [JsonPropertyName("updated_at")] public string UpdatedAt { get; set; } = "";
    [JsonPropertyName("files")] public List<CloudUsbMusicFile> Files { get; set; } = new();
}

public static class UsbMusicCloudService
{
    private const string Endpoint = "https://rubenvanaggelen.com/the-one-remote-api/music.php";
    private const string Pin = "1207";

    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromMinutes(8)
    };

    private static readonly SemaphoreSlim SyncGate = new(1, 1);
    private static readonly HashSet<string> AudioExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac", ".wav", ".wma", ".mp4"
    };

    private static CancellationTokenSource? _backgroundCts;
    private static string _token = "";
    private static DateTime _tokenValidUntil = DateTime.MinValue;
    private static string _lastDriveSignature = "";

    public static void StartBackgroundSync()
    {
        if (_backgroundCts != null) return;
        _backgroundCts = new CancellationTokenSource();
        _ = Task.Run(() => BackgroundLoopAsync(_backgroundCts.Token));
    }

    private static async Task BackgroundLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                var signature = BuildDriveSignature();
                if (!string.Equals(signature, _lastDriveSignature, StringComparison.Ordinal))
                {
                    await SyncNowAsync(cancellationToken);
                    _lastDriveSignature = signature;
                }
            }
            catch
            {
                // Netwerk of USB mag The One Windows nooit blokkeren.
            }

            try
            {
                await Task.Delay(TimeSpan.FromSeconds(20), cancellationToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    public static async Task<int> SyncNowAsync(CancellationToken cancellationToken = default)
    {
        if (!await SyncGate.WaitAsync(0, cancellationToken)) return 0;
        try
        {
            var drives = ReadyUsbDrives().ToList();
            if (drives.Count == 0) return 0;

            var catalog = await GetCatalogAsync(cancellationToken);
            var existing = catalog
                .SelectMany(stick => stick.Files.Select(file =>
                {
                    file.DeviceId = stick.DeviceId;
                    file.StickId = stick.StickId;
                    return file;
                }))
                .ToDictionary(
                    file => $"{file.DeviceId}|{file.StickId}|{NormalizePath(file.Path)}",
                    file => file,
                    StringComparer.OrdinalIgnoreCase);

            var synced = 0;
            foreach (var drive in drives)
            {
                cancellationToken.ThrowIfCancellationRequested();
                synced += await SyncDriveAsync(drive, existing, cancellationToken);
            }

            return synced;
        }
        finally
        {
            SyncGate.Release();
        }
    }

    public static async Task<List<CloudUsbMusicStick>> GetCatalogAsync(
        CancellationToken cancellationToken = default)
    {
        await EnsureTokenAsync(cancellationToken);

        using var request = new HttpRequestMessage(
            HttpMethod.Get,
            Endpoint + "?action=catalog");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _token);

        using var response = await Http.SendAsync(request, cancellationToken);
        if (response.StatusCode == System.Net.HttpStatusCode.Unauthorized)
        {
            _token = "";
            await EnsureTokenAsync(cancellationToken);
            return await GetCatalogAsync(cancellationToken);
        }

        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var json = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);

        var list = new List<CloudUsbMusicStick>();
        if (!json.RootElement.TryGetProperty("sticks", out var sticks) ||
            sticks.ValueKind != JsonValueKind.Array)
            return list;

        foreach (var element in sticks.EnumerateArray())
        {
            var stick = element.Deserialize<CloudUsbMusicStick>();
            if (stick == null) continue;
            foreach (var file in stick.Files)
            {
                file.DeviceId = stick.DeviceId;
                file.StickId = stick.StickId;
            }
            list.Add(stick);
        }

        return list
            .OrderBy(x => x.DeviceName, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(x => x.StickName, StringComparer.CurrentCultureIgnoreCase)
            .ToList();
    }

    public static async Task<string> BuildStreamUrlAsync(
        CloudUsbMusicFile file,
        CancellationToken cancellationToken = default)
    {
        await EnsureTokenAsync(cancellationToken);
        return Endpoint +
            "?action=stream" +
            "&token=" + Uri.EscapeDataString(_token) +
            "&device=" + Uri.EscapeDataString(file.DeviceId) +
            "&stick=" + Uri.EscapeDataString(file.StickId) +
            "&path=" + Uri.EscapeDataString(NormalizePath(file.Path));
    }

    private static async Task<int> SyncDriveAsync(
        DriveInfo drive,
        Dictionary<string, CloudUsbMusicFile> existing,
        CancellationToken cancellationToken)
    {
        var deviceId = SafeId(Environment.MachineName);
        var deviceName = FriendlyDeviceName(Environment.MachineName);
        var stickName = string.IsNullOrWhiteSpace(drive.VolumeLabel)
            ? $"USB {drive.Name.TrimEnd('\\')}"
            : drive.VolumeLabel.Trim();
        var stickId = StickId(drive);

        var preferred = Path.Combine(drive.RootDirectory.FullName, "Muziek");
        var scanRoot = Directory.Exists(preferred)
            ? preferred
            : drive.RootDirectory.FullName;

        var files = EnumerateAudioFiles(scanRoot).ToList();
        if (files.Count == 0)
        {
            await SendManifestAsync(
                deviceId, deviceName, stickId, stickName,
                new List<LocalManifestFile>(), cancellationToken);
            return 0;
        }

        var manifest = new List<LocalManifestFile>();
        var uploaded = 0;

        foreach (var file in files)
        {
            cancellationToken.ThrowIfCancellationRequested();

            var relative = NormalizePath(Path.GetRelativePath(scanRoot, file.FullName));
            var modified = file.LastWriteTimeUtc.ToString("O");
            var lookup = $"{deviceId}|{stickId}|{relative}";

            string sha;
            var cached = existing.TryGetValue(lookup, out var old) &&
                         old.Cached &&
                         old.Size == file.Length &&
                         string.Equals(old.Modified, modified, StringComparison.Ordinal) &&
                         !string.IsNullOrWhiteSpace(old.Sha256);

            if (cached)
            {
                sha = old!.Sha256;
            }
            else
            {
                sha = await HashFileAsync(file.FullName, cancellationToken);
                var needsUpload =
                    old == null ||
                    !old.Cached ||
                    !string.Equals(old.Sha256, sha, StringComparison.OrdinalIgnoreCase);

                if (needsUpload)
                {
                    await UploadAsync(
                        deviceId, stickId, relative, sha, file.FullName, cancellationToken);
                    uploaded++;
                }
            }

            manifest.Add(new LocalManifestFile
            {
                Path = relative,
                Size = file.Length,
                Sha256 = sha,
                Modified = modified
            });
        }

        await SendManifestAsync(
            deviceId, deviceName, stickId, stickName, manifest, cancellationToken);

        return uploaded;
    }

    private static async Task UploadAsync(
        string deviceId,
        string stickId,
        string relativePath,
        string sha256,
        string filePath,
        CancellationToken cancellationToken)
    {
        await EnsureTokenAsync(cancellationToken);

        using var form = new MultipartFormDataContent();
        form.Add(new StringContent(deviceId), "device_id");
        form.Add(new StringContent(stickId), "stick_id");
        form.Add(new StringContent(relativePath), "path");
        form.Add(new StringContent(sha256), "sha256");

        await using var source = new FileStream(
            filePath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.ReadWrite,
            1024 * 1024,
            useAsync: true);

        using var fileContent = new StreamContent(source);
        fileContent.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        form.Add(fileContent, "file", Path.GetFileName(filePath));

        using var request = new HttpRequestMessage(
            HttpMethod.Post,
            Endpoint + "?action=upload")
        {
            Content = form
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _token);

        using var response = await Http.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken);
        response.EnsureSuccessStatusCode();
    }

    private static async Task SendManifestAsync(
        string deviceId,
        string deviceName,
        string stickId,
        string stickName,
        List<LocalManifestFile> files,
        CancellationToken cancellationToken)
    {
        await EnsureTokenAsync(cancellationToken);

        var body = JsonSerializer.Serialize(new
        {
            device_id = deviceId,
            device_name = deviceName,
            stick_id = stickId,
            stick_name = stickName,
            files = files.Select(x => new
            {
                path = x.Path,
                size = x.Size,
                sha256 = x.Sha256,
                modified = x.Modified
            })
        });

        using var request = new HttpRequestMessage(
            HttpMethod.Post,
            Endpoint + "?action=sync")
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json")
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _token);

        using var response = await Http.SendAsync(request, cancellationToken);
        response.EnsureSuccessStatusCode();
    }

    private static async Task EnsureTokenAsync(CancellationToken cancellationToken)
    {
        if (!string.IsNullOrWhiteSpace(_token) &&
            DateTime.UtcNow < _tokenValidUntil)
            return;

        var body = JsonSerializer.Serialize(new { pin = Pin });
        using var response = await Http.PostAsync(
            Endpoint + "?action=login",
            new StringContent(body, Encoding.UTF8, "application/json"),
            cancellationToken);

        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var json = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);

        _token = json.RootElement.GetProperty("token").GetString() ?? "";
        var seconds = json.RootElement.TryGetProperty("expires_in", out var expires)
            ? expires.GetInt32()
            : 3600;
        _tokenValidUntil = DateTime.UtcNow.AddSeconds(Math.Max(60, seconds - 120));
    }

    private static IEnumerable<DriveInfo> ReadyUsbDrives()
    {
        foreach (var drive in DriveInfo.GetDrives())
        {
            bool ready;
            try { ready = drive.IsReady; }
            catch { ready = false; }

            if (!ready || drive.DriveType != DriveType.Removable)
                continue;

            yield return drive;
        }
    }

    private static IEnumerable<FileInfo> EnumerateAudioFiles(string root)
    {
        var options = new EnumerationOptions
        {
            RecurseSubdirectories = true,
            IgnoreInaccessible = true,
            ReturnSpecialDirectories = false,
            AttributesToSkip = FileAttributes.ReparsePoint | FileAttributes.System
        };

        IEnumerable<string> paths;
        try { paths = Directory.EnumerateFiles(root, "*", options); }
        catch { yield break; }

        foreach (var path in paths)
        {
            if (!AudioExtensions.Contains(System.IO.Path.GetExtension(path)))
                continue;

            FileInfo info;
            try { info = new FileInfo(path); }
            catch { continue; }

            yield return info;
        }
    }

    private static string BuildDriveSignature()
    {
        var parts = new List<string>();
        foreach (var drive in ReadyUsbDrives())
        {
            try
            {
                var preferred = Path.Combine(drive.RootDirectory.FullName, "Muziek");
                var scanRoot = Directory.Exists(preferred)
                    ? preferred
                    : drive.RootDirectory.FullName;

                var count = 0;
                long bytes = 0;
                long newest = 0;

                foreach (var file in EnumerateAudioFiles(scanRoot))
                {
                    count++;
                    bytes += file.Length;
                    newest = Math.Max(newest, file.LastWriteTimeUtc.Ticks);
                }

                parts.Add($"{drive.Name}|{drive.VolumeLabel}|{drive.TotalSize}|{count}|{bytes}|{newest}");
            }
            catch { }
        }

        return string.Join(";", parts.OrderBy(x => x, StringComparer.OrdinalIgnoreCase));
    }

    private static async Task<string> HashFileAsync(
        string path,
        CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(
            path,
            FileMode.Open,
            FileAccess.Read,
            FileShare.ReadWrite,
            1024 * 1024,
            useAsync: true);

        using var sha = SHA256.Create();
        var hash = await sha.ComputeHashAsync(stream, cancellationToken);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static string StickId(DriveInfo drive)
    {
        var raw = $"{drive.VolumeLabel}|{drive.TotalSize}";
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(raw));
        return Convert.ToHexString(hash).ToLowerInvariant()[..20];
    }

    private static string SafeId(string value)
    {
        var b = new StringBuilder();
        foreach (var ch in value)
            b.Append(char.IsLetterOrDigit(ch) || ch is '.' or '_' or '-' ? ch : '-');
        return b.ToString().Trim('-').IfBlank("device");
    }

    private static string FriendlyDeviceName(string machine)
    {
        if (machine.Equals("Ruben", StringComparison.OrdinalIgnoreCase))
            return "Ruben";
        if (machine.Equals("TABLET-042GE173", StringComparison.OrdinalIgnoreCase) ||
            machine.Contains("SURFACE", StringComparison.OrdinalIgnoreCase))
            return "Surface";
        return machine;
    }

    private static string NormalizePath(string path) =>
        path.Replace('\\', '/').TrimStart('/');

    private sealed class LocalManifestFile
    {
        public string Path { get; set; } = "";
        public long Size { get; set; }
        public string Sha256 { get; set; } = "";
        public string Modified { get; set; } = "";
    }
}
