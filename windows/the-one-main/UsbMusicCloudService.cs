using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace TheOneMain.Windows;

public sealed class CloudUsbMusicFile
{
    [JsonPropertyName("path")] public string Path { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("folder")] public string Folder { get; set; } = "";
    [JsonPropertyName("title")] public string Title { get; set; } = "";
    [JsonPropertyName("artist")] public string Artist { get; set; } = "";
    [JsonPropertyName("album")] public string Album { get; set; } = "";
    [JsonPropertyName("size")] public long Size { get; set; }
    [JsonPropertyName("sha256")] public string Sha256 { get; set; } = "";
    [JsonPropertyName("modified")] public string Modified { get; set; } = "";
    [JsonPropertyName("cached")] public bool Cached { get; set; }
    [JsonIgnore] public string DeviceId { get; set; } = "";
    [JsonIgnore] public string StickId { get; set; } = "";
    [JsonIgnore] public string DisplayName
    {
        get
        {
            var value = string.IsNullOrWhiteSpace(Title) ? Name : Title.Trim();
            var extensions = new[]
            {
                ".mp3", ".wma", ".m4a", ".aac", ".flac",
                ".ogg", ".oga", ".opus", ".wav", ".mp4"
            };
            foreach (var extension in extensions)
            {
                if (value.EndsWith(extension, StringComparison.OrdinalIgnoreCase))
                {
                    value = value[..^extension.Length];
                    break;
                }
            }
            return value.Replace('_', ' ').Trim();
        }
    }
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
    private const string DeviceEndpoint = "https://rubenvanaggelen.com/the-one-remote-api/devices.php";
    private const string Pin = "1207";

    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromMinutes(8)
    };

    private static readonly SemaphoreSlim SyncGate = new(1, 1);
    private static readonly string DiagnosticLog = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "The One Family",
        "The One Windows",
        "usb-music-sync.log");
    private static readonly string StickIdentityMap = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "The One Family",
        "The One Windows",
        "usb-stick-identities.json");
    private static readonly HashSet<string> AudioExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac", ".wav", ".wma"
    };

    private static CancellationTokenSource? _backgroundCts;
    private static string _token = "";
    private static DateTime _tokenValidUntil = DateTime.MinValue;
    private static string _lastDriveSignature = "__not_initialized__";

    public static void StartBackgroundSync()
    {
        if (_backgroundCts != null) return;
        Log("Background sync gestart.");
        _backgroundCts = new CancellationTokenSource();
        _ = Task.Run(() => BackgroundLoopAsync(_backgroundCts.Token));
    }

    private static async Task BackgroundLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await ReportWindowsHeartbeatAsync(cancellationToken);
                await PullDjQueueAsync(cancellationToken);

                var signature = BuildDriveSignature();
                if (!string.Equals(signature, _lastDriveSignature, StringComparison.Ordinal))
                {
                    Log("Shared Media-wijziging gedetecteerd. Synchronisatie starten.");
                    await SyncNowAsync(cancellationToken);
                    _lastDriveSignature = signature;
                }
            }
            catch (Exception ex)
            {
                Log("Achtergrondsync fout: " + ex.GetType().Name + " - " + ex.Message);
                // Netwerk of Shared Media mag The One Windows nooit blokkeren.
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

    private static async Task ReportWindowsHeartbeatAsync(CancellationToken cancellationToken)
    {
        try
        {
            var rawMachine = Environment.MachineName;
            var displayName =
                Environment.UserName.Equals("Surface Pro", StringComparison.OrdinalIgnoreCase) ||
                rawMachine.StartsWith("TABLET-", StringComparison.OrdinalIgnoreCase)
                    ? "Surface"
                    : rawMachine.Equals("Ruben", StringComparison.OrdinalIgnoreCase)
                        ? "Ruben"
                        : rawMachine;

            var body = JsonSerializer.Serialize(new
            {
                device_id = "windows-" + SafeId(rawMachine),
                name = displayName,
                person_name = displayName,
                device_role = "windows",
                platform = "Windows",
                version = ""
            });

            using var request = new HttpRequestMessage(
                HttpMethod.Post,
                DeviceEndpoint + "?action=heartbeat")
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json")
            };
            using var response = await Http.SendAsync(request, cancellationToken);
            response.EnsureSuccessStatusCode();
        }
        catch when (!cancellationToken.IsCancellationRequested)
        {
            // Statusmelding mag Shared Media nooit blokkeren.
        }
    }

    private static string CurrentDjDeviceId() =>
        "windows-" + SafeId(Environment.MachineName).ToLowerInvariant();

    public static string UsbFavoriteKey(CloudUsbMusicFile file) =>
        file.DeviceId + "\n" + file.StickId + "\n" + NormalizePath(file.Path);

    public static async Task<HashSet<string>> GetUsbFavoriteKeysAsync(
        CancellationToken cancellationToken = default)
    {
        await EnsureTokenAsync(cancellationToken);
        var requestDeviceId = CurrentDjDeviceId();

        using var request = new HttpRequestMessage(
            HttpMethod.Get,
            Endpoint + "?action=favorites-list&request_device_id=" +
            Uri.EscapeDataString(requestDeviceId));
        request.Headers.Authorization =
            new AuthenticationHeaderValue("Bearer", _token);

        using var response = await Http.SendAsync(request, cancellationToken);
        if (response.StatusCode == System.Net.HttpStatusCode.Unauthorized)
        {
            _token = "";
            await EnsureTokenAsync(cancellationToken);
            return await GetUsbFavoriteKeysAsync(cancellationToken);
        }

        response.EnsureSuccessStatusCode();
        var payload = await response.Content.ReadAsStringAsync(cancellationToken);
        using var json = JsonDocument.Parse(payload);
        var keys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        if (!json.RootElement.TryGetProperty("items", out var items) ||
            items.ValueKind != JsonValueKind.Array)
            return keys;

        foreach (var item in items.EnumerateArray())
        {
            if (!item.TryGetProperty("kind", out var kind) ||
                !string.Equals(kind.GetString(), "usb", StringComparison.OrdinalIgnoreCase))
                continue;

            var device = item.TryGetProperty("device_id", out var d) ? d.GetString() ?? "" : "";
            var stick = item.TryGetProperty("stick_id", out var s) ? s.GetString() ?? "" : "";
            var path = item.TryGetProperty("path", out var p) ? p.GetString() ?? "" : "";
            if (device.Length == 0 || stick.Length == 0 || path.Length == 0) continue;
            keys.Add(device + "\n" + stick + "\n" + NormalizePath(path));
        }

        return keys;
    }

    public static async Task SetUsbFavoriteAsync(
        CloudUsbMusicStick stick,
        CloudUsbMusicFile file,
        bool favorite,
        CancellationToken cancellationToken = default)
    {
        await EnsureTokenAsync(cancellationToken);
        var body = JsonSerializer.Serialize(new
        {
            request_device_id = CurrentDjDeviceId(),
            kind = "usb",
            title = file.DisplayName,
            source_label = "Shared Media • " + stick.DeviceName + " • " + stick.StickName,
            device_id = file.DeviceId,
            stick_id = file.StickId,
            path = file.Path,
            favorite
        });

        using var request = new HttpRequestMessage(
            HttpMethod.Post,
            Endpoint + "?action=favorites-set")
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json")
        };
        request.Headers.Authorization =
            new AuthenticationHeaderValue("Bearer", _token);

        using var response = await Http.SendAsync(request, cancellationToken);
        if (response.StatusCode == System.Net.HttpStatusCode.Unauthorized)
        {
            _token = "";
            await EnsureTokenAsync(cancellationToken);
            throw new InvalidOperationException("Favoriet opnieuw proberen.");
        }
        response.EnsureSuccessStatusCode();
    }

    public static async Task QueueDjImportAsync(
        CloudUsbMusicFile file,
        CancellationToken cancellationToken = default)
    {
        await EnsureTokenAsync(cancellationToken);

        var body = JsonSerializer.Serialize(new
        {
            request_device_id = CurrentDjDeviceId(),
            device_id = file.DeviceId,
            stick_id = file.StickId,
            path = file.Path,
            name = file.Name,
            title = file.DisplayName
        });

        using var request = new HttpRequestMessage(
            HttpMethod.Post,
            Endpoint + "?action=dj-queue-add")
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json")
        };
        request.Headers.Authorization =
            new AuthenticationHeaderValue("Bearer", _token);

        using var response = await Http.SendAsync(request, cancellationToken);
        if (response.StatusCode == System.Net.HttpStatusCode.Unauthorized)
        {
            _token = "";
            await EnsureTokenAsync(cancellationToken);
            throw new InvalidOperationException("DJ-import opnieuw proberen.");
        }

        var payload = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            try
            {
                using var json = JsonDocument.Parse(payload);
                if (json.RootElement.TryGetProperty("error", out var error))
                    throw new InvalidOperationException(error.GetString() ?? "Naar DJ sturen mislukt.");
            }
            catch (JsonException) { }

            throw new InvalidOperationException(
                $"Naar DJ sturen mislukt ({(int)response.StatusCode}).");
        }

        Log("DJ-import verstuurd vanaf " + CurrentDjDeviceId() + ": " + file.DisplayName);
    }

    private static async Task PullDjQueueAsync(CancellationToken cancellationToken)
    {

        var djRoot = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Programs",
            "The One Family",
            "The One DJ");
        if (!Directory.Exists(djRoot)) return;

        await EnsureTokenAsync(cancellationToken);

        var currentDjDeviceId = CurrentDjDeviceId();
        using var request = new HttpRequestMessage(
            HttpMethod.Get,
            Endpoint + "?action=dj-queue-list&request_device_id=" +
            Uri.EscapeDataString(currentDjDeviceId));
        request.Headers.Authorization =
            new AuthenticationHeaderValue("Bearer", _token);

        using var response = await Http.SendAsync(request, cancellationToken);
        if (response.StatusCode == System.Net.HttpStatusCode.Unauthorized)
        {
            _token = "";
            await EnsureTokenAsync(cancellationToken);
            return;
        }
        response.EnsureSuccessStatusCode();

        var payload = await response.Content.ReadAsStringAsync(cancellationToken);
        using var json = JsonDocument.Parse(payload);
        if (!json.RootElement.TryGetProperty("items", out var items) ||
            items.ValueKind != JsonValueKind.Array)
            return;

        var importDir = Path.Combine(djRoot, "app", "imports");
        Directory.CreateDirectory(importDir);
        var manifestPath = Path.Combine(importDir, "shared-media.json");

        foreach (var item in items.EnumerateArray())
        {
            cancellationToken.ThrowIfCancellationRequested();

            var id = item.TryGetProperty("id", out var idEl)
                ? idEl.GetString() ?? ""
                : "";
            var device = item.TryGetProperty("device_id", out var deviceEl)
                ? deviceEl.GetString() ?? ""
                : "";
            var stick = item.TryGetProperty("stick_id", out var stickEl)
                ? stickEl.GetString() ?? ""
                : "";
            var relativePath = item.TryGetProperty("path", out var pathEl)
                ? pathEl.GetString() ?? ""
                : "";
            var name = item.TryGetProperty("name", out var nameEl)
                ? nameEl.GetString() ?? ""
                : "";

            if (id.Length == 0 || device.Length == 0 ||
                stick.Length == 0 || relativePath.Length == 0)
                continue;

            if (string.IsNullOrWhiteSpace(name))
                name = Path.GetFileName(relativePath);
            var safeName = string.Concat(
                name.Select(ch =>
                    Path.GetInvalidFileNameChars().Contains(ch) ? '_' : ch))
                .Trim();
            if (safeName.Length == 0)
                safeName = "TheOne-nummer.mp3";

            var identity = device + "\n" + stick + "\n" + relativePath;
            var prefix = Convert.ToHexString(
                    SHA256.HashData(Encoding.UTF8.GetBytes(identity)))
                .ToLowerInvariant()[..12];
            var storedName = prefix + "-" + safeName;
            var targetPath = Path.Combine(importDir, storedName);

            if (!File.Exists(targetPath))
            {
                var file = new CloudUsbMusicFile
                {
                    DeviceId = device,
                    StickId = stick,
                    Path = relativePath,
                    Name = name
                };
                var url = await BuildStreamUrlAsync(file, cancellationToken);
                using var media = await Http.GetAsync(
                    url,
                    HttpCompletionOption.ResponseHeadersRead,
                    cancellationToken);
                media.EnsureSuccessStatusCode();

                await using var source =
                    await media.Content.ReadAsStreamAsync(cancellationToken);
                await using var target = new FileStream(
                    targetPath,
                    FileMode.Create,
                    FileAccess.Write,
                    FileShare.Read,
                    128 * 1024,
                    useAsync: true);
                await source.CopyToAsync(target, cancellationToken);
            }

            List<string> imports;
            try
            {
                imports = File.Exists(manifestPath)
                    ? JsonSerializer.Deserialize<List<string>>(
                        await File.ReadAllTextAsync(manifestPath, cancellationToken)) ?? new()
                    : new();
            }
            catch
            {
                imports = new();
            }

            if (!imports.Contains(storedName, StringComparer.OrdinalIgnoreCase))
            {
                imports.Add(storedName);
                await File.WriteAllTextAsync(
                    manifestPath,
                    JsonSerializer.Serialize(imports),
                    cancellationToken);
            }

            var ackBody = JsonSerializer.Serialize(new
            {
                id,
                request_device_id = currentDjDeviceId
            });
            using var ack = new HttpRequestMessage(
                HttpMethod.Post,
                Endpoint + "?action=dj-queue-ack")
            {
                Content = new StringContent(ackBody, Encoding.UTF8, "application/json")
            };
            ack.Headers.Authorization =
                new AuthenticationHeaderValue("Bearer", _token);
            using var ackResponse = await Http.SendAsync(ack, cancellationToken);
            ackResponse.EnsureSuccessStatusCode();

            Log("DJ-import ontvangen: " + storedName);
        }
    }

    public static async Task<int> SyncNowAsync(CancellationToken cancellationToken = default)
    {
        if (!await SyncGate.WaitAsync(0, cancellationToken)) return 0;
        try
        {
            var drives = ReadyUsbDrives().ToList();
            Log($"Shared Media-drives gevonden: {drives.Count}.");

            if (drives.Count == 0)
            {
                await ReportPresenceAsync(drives, new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase), cancellationToken);
                return 0;
            }

            // Lees ook verborgen/inactieve catalogusdata. Daarmee kunnen we een USB-stick
            // waarvan alleen de zichtbare naam veranderde terugkoppelen aan zijn bestaande
            // Family-identiteit, inclusief reeds gecontroleerde/gecachete nummers.
            var catalog = await GetCatalogAsync(cancellationToken, includeInactive: true);
            var resolvedStickIds = drives.ToDictionary(
                drive => drive.RootDirectory.FullName,
                drive => ResolveStickId(drive, catalog),
                StringComparer.OrdinalIgnoreCase);

            // Meld altijd welke fysieke sticks aanwezig zijn, maar gebruik voortaan de
            // stabiele/canonieke identiteit in plaats van de zichtbare volumenaam.
            await ReportPresenceAsync(drives, resolvedStickIds, cancellationToken);
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
                synced += await SyncDriveAsync(
                    drive,
                    resolvedStickIds[drive.RootDirectory.FullName],
                    existing,
                    cancellationToken);
            }

            return synced;
        }
        finally
        {
            SyncGate.Release();
        }
    }

    private static async Task ReportPresenceAsync(
        List<DriveInfo> drives,
        IReadOnlyDictionary<string, string> resolvedStickIds,
        CancellationToken cancellationToken)
    {
        await EnsureTokenAsync(cancellationToken);

        var body = JsonSerializer.Serialize(new
        {
            device_id = SafeId(Environment.MachineName),
            active_stick_ids = drives
                .Select(drive => resolvedStickIds.TryGetValue(drive.RootDirectory.FullName, out var id)
                    ? id
                    : LegacyStickId(drive))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToArray()
        });

        async Task<HttpResponseMessage> SendAsync()
        {
            var request = new HttpRequestMessage(
                HttpMethod.Post,
                Endpoint + "?action=presence");
            request.Headers.Authorization =
                new AuthenticationHeaderValue("Bearer", _token);
            request.Content =
                new StringContent(body, Encoding.UTF8, "application/json");
            return await Http.SendAsync(request, cancellationToken);
        }

        using var response = await SendAsync();
        if (response.StatusCode == System.Net.HttpStatusCode.Unauthorized)
        {
            _token = "";
            await EnsureTokenAsync(cancellationToken);

            using var retry = new HttpRequestMessage(
                HttpMethod.Post,
                Endpoint + "?action=presence");
            retry.Headers.Authorization =
                new AuthenticationHeaderValue("Bearer", _token);
            retry.Content =
                new StringContent(body, Encoding.UTF8, "application/json");

            using var retryResponse =
                await Http.SendAsync(retry, cancellationToken);
            retryResponse.EnsureSuccessStatusCode();
        }
        else
        {
            response.EnsureSuccessStatusCode();
        }

        Log($"Shared Media-aanwezigheid gepubliceerd: {drives.Count} actieve stick(s).");
    }

    public static async Task<bool> ValidateUserPinAsync(
        string pin,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(pin)) return false;

        try
        {
            var body = JsonSerializer.Serialize(new { pin = pin.Trim() });
            using var response = await Http.PostAsync(
                Endpoint + "?action=login",
                new StringContent(body, Encoding.UTF8, "application/json"),
                cancellationToken);

            if (!response.IsSuccessStatusCode)
                return false;

            await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
            using var json = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);

            var token = json.RootElement.GetProperty("token").GetString() ?? "";
            if (string.IsNullOrWhiteSpace(token))
                return false;

            _token = token;
            var seconds = json.RootElement.TryGetProperty("expires_in", out var expires)
                ? expires.GetInt32()
                : 3600;
            _tokenValidUntil = DateTime.UtcNow.AddSeconds(Math.Max(60, seconds - 120));
            return true;
        }
        catch
        {
            return false;
        }
    }

    public static async Task<List<CloudUsbMusicStick>> GetCatalogAsync(
        CancellationToken cancellationToken = default,
        bool includeInactive = false)
    {
        await EnsureTokenAsync(cancellationToken);

        var catalogUrl = Endpoint + "?action=catalog" +
            (includeInactive ? "&include_inactive=1" : "");

        using var request = new HttpRequestMessage(
            HttpMethod.Get,
            catalogUrl);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _token);

        using var response = await Http.SendAsync(request, cancellationToken);
        if (response.StatusCode == System.Net.HttpStatusCode.Unauthorized)
        {
            _token = "";
            await EnsureTokenAsync(cancellationToken);
            return await GetCatalogAsync(cancellationToken, includeInactive);
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
            stick.Files = stick.Files
                .Where(file => !IsMacMetadataPath(file.Path, file.Name))
                .ToList();

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

    public static async Task<(bool Ok, string Message)> ProbeStreamAsync(
        CloudUsbMusicFile file,
        CancellationToken cancellationToken = default)
    {
        try
        {
            var url = await BuildStreamUrlAsync(file, cancellationToken);
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.Range = new RangeHeaderValue(0, 0);

            using var response = await Http.SendAsync(
                request,
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken);

            if (response.StatusCode == System.Net.HttpStatusCode.OK ||
                response.StatusCode == System.Net.HttpStatusCode.PartialContent)
            {
                return (true, $"HTTP {(int)response.StatusCode}");
            }

            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            return (false, $"HTTP {(int)response.StatusCode}: {body}");
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    private static async Task<int> SyncDriveAsync(
        DriveInfo drive,
        string stickId,
        Dictionary<string, CloudUsbMusicFile> existing,
        CancellationToken cancellationToken)
    {
        var deviceId = SafeId(Environment.MachineName);
        var deviceName = FriendlyDeviceName(Environment.MachineName);
        var stickName = string.IsNullOrWhiteSpace(drive.VolumeLabel)
            ? $"USB {drive.Name.TrimEnd('\\')}"
            : drive.VolumeLabel.Trim();
        var preferred = Path.Combine(drive.RootDirectory.FullName, "Muziek");
        var scanRoot = Directory.Exists(preferred)
            ? preferred
            : drive.RootDirectory.FullName;

        var files = EnumerateAudioFiles(scanRoot)
            .OrderBy(file => file.Length)
            .ThenBy(file => file.FullName, StringComparer.CurrentCultureIgnoreCase)
            .ToList();

        var metadata = files.ToDictionary(
            file => file.FullName,
            file => ReadMetadata(file.FullName),
            StringComparer.OrdinalIgnoreCase);

        Log($"{deviceName} / {stickName}: {files.Count} audiobestanden gevonden in {scanRoot}.");

        if (files.Count == 0)
        {
            Log($"{deviceName} / {stickName}: geen audiobestanden gevonden; bestaande Family-catalogus blijft behouden.");
            return 0;
        }

        // Publiceer eerst snel alleen de actuele paden/metadata.
        // sync-batch vervangt de servercatalogus pas na de laatste batch,
        // dus dit kan de bibliotheek niet half/leeg achterlaten.
        var recoveryManifest = files.Select(file =>
        {
            var relative = NormalizePath(Path.GetRelativePath(scanRoot, file.FullName));
            var tags = metadata[file.FullName];
            return new LocalManifestFile
            {
                Path = relative,
                Size = file.Length,
                Sha256 = "",
                Modified = file.LastWriteTimeUtc.ToString("O"),
                Title = tags.Title,
                Artist = tags.Artist,
                Album = tags.Album
            };
        }).ToList();

        await SendManifestAsync(
            deviceId, deviceName, stickId, stickName,
            recoveryManifest, cancellationToken);
        Log($"{deviceName} / {stickName}: snelle herstelcatalogus gepubliceerd.");

        var finalManifest = new LocalManifestFile[files.Count];

        // Herstel hashes parallel; dit is veel sneller dan 1360+ bestanden
        // één voor één lezen en blokkeert de veilige catalogusherstelactie niet onnodig.
        using (var hashGate = new SemaphoreSlim(8, 8))
        {
            var manifestTasks = files.Select(async (file, fileIndex) =>
            {
                await hashGate.WaitAsync(cancellationToken);
                try
                {
                    cancellationToken.ThrowIfCancellationRequested();

                    var relative = NormalizePath(
                        Path.GetRelativePath(scanRoot, file.FullName));
                    var modified = file.LastWriteTimeUtc.ToString("O");
                    var lookup = $"{deviceId}|{stickId}|{relative}";

                    var hasOld = existing.TryGetValue(lookup, out var old);
                    var unchanged =
                        hasOld &&
                        old!.Size == file.Length &&
                        string.Equals(old.Modified, modified, StringComparison.Ordinal) &&
                        !string.IsNullOrWhiteSpace(old.Sha256);

                    var sha = unchanged
                        ? old!.Sha256
                        : await HashFileAsync(file.FullName, cancellationToken);

                    var tags = metadata[file.FullName];
                    finalManifest[fileIndex] = new LocalManifestFile
                    {
                        Path = relative,
                        Size = file.Length,
                        Sha256 = sha,
                        Modified = modified,
                        Title = tags.Title,
                        Artist = tags.Artist,
                        Album = tags.Album
                    };
                }
                finally
                {
                    hashGate.Release();
                }
            }).ToList();

            await Task.WhenAll(manifestTasks);
        }

        var finalManifestList = finalManifest.ToList();

        // Publiceer eerst de volledige catalogus met echte hashes. Bestaande
        // serverblobs kunnen hierdoor direct opnieuw aan de nummers gekoppeld worden.
        await SendManifestAsync(
            deviceId, deviceName, stickId, stickName,
            finalManifestList, cancellationToken);
        Log($"{deviceName} / {stickName}: volledige catalogus veilig gepubliceerd.");

        // Vraag daarna opnieuw aan de server wat werkelijk al gecachet is.
        // Zo uploaden we geen bestanden opnieuw als alleen de cataloguskoppeling
        // verloren was.
        var refreshedCatalog =
            await GetCatalogAsync(cancellationToken, includeInactive: true);
        var refreshed = refreshedCatalog
            .Where(x =>
                x.DeviceId.Equals(deviceId, StringComparison.OrdinalIgnoreCase) &&
                x.StickId.Equals(stickId, StringComparison.OrdinalIgnoreCase))
            .SelectMany(x => x.Files)
            .ToDictionary(
                x => NormalizePath(x.Path),
                x => x,
                StringComparer.OrdinalIgnoreCase);

        var uploadJobs = new List<(FileInfo File, string Relative, string Sha)>();
        for (var i = 0; i < files.Count; i++)
        {
            var file = files[i];
            var manifest = finalManifest[i];
            if (!refreshed.TryGetValue(manifest.Path, out var serverFile) ||
                !serverFile.Cached ||
                !string.Equals(
                    serverFile.Sha256,
                    manifest.Sha256,
                    StringComparison.OrdinalIgnoreCase))
            {
                uploadJobs.Add((file, manifest.Path, manifest.Sha256));
            }
        }

        Log($"{deviceName} / {stickName}: {finalManifestList.Count - uploadJobs.Count} bestand(en) uit bestaande cache hersteld; {uploadJobs.Count} upload(s) nog nodig.");

        // Upload bewust conservatief: kleine chunks en maximaal 4 tegelijk.
        // De hostinglaag kapte grotere JSON-chunks af, waardoor upload-chunk
        // als lege/malformed request binnenkwam en "invalid id" gaf.
        // Mislukte bestanden gaan nog één keer met maximaal 2 tegelijk.
        var uploaded = 0;

        async Task<List<(FileInfo File, string Relative, string Sha)>> UploadBatchAsync(
            List<(FileInfo File, string Relative, string Sha)> jobs,
            int concurrency)
        {
            var failed = new List<(FileInfo File, string Relative, string Sha)>();
            using var gate = new SemaphoreSlim(concurrency, concurrency);

            var tasks = jobs.Select(async job =>
            {
                await gate.WaitAsync(cancellationToken);
                try
                {
                    await UploadAsync(
                        deviceId,
                        stickId,
                        job.Relative,
                        job.Sha,
                        job.File.FullName,
                        cancellationToken);

                    var done = Interlocked.Increment(ref uploaded);
                    if (done == 1 || done % 25 == 0 || done == uploadJobs.Count)
                        Log($"{deviceName} / {stickName}: {done}/{uploadJobs.Count} bestand(en) geüpload.");
                }
                catch (Exception ex) when (!cancellationToken.IsCancellationRequested)
                {
                    lock (failed)
                        failed.Add(job);

                    Log($"{deviceName} / {stickName}: upload mislukt bij {concurrency} parallel - {job.Relative} - {ex.Message}");
                }
                finally
                {
                    gate.Release();
                }
            }).ToList();

            await Task.WhenAll(tasks);
            return failed;
        }

        var failedAt16 = await UploadBatchAsync(uploadJobs, 16);
        if (failedAt16.Count > 0)
        {
            Log($"{deviceName} / {stickName}: {failedAt16.Count} upload(s) mislukt op 16 parallel; automatisch terug naar 12.");
            var failedAt12 = await UploadBatchAsync(failedAt16, 12);

            if (failedAt12.Count > 0)
                throw new HttpRequestException(
                    $"{failedAt12.Count} USB-upload(s) mislukten ook na terugval naar 12; volgende sync probeert opnieuw.");
        }

        await SendManifestAsync(
            deviceId, deviceName, stickId, stickName, finalManifestList, cancellationToken);

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

        async Task<JsonDocument> PostJsonAsync(string action, object body)
        {
            var json = JsonSerializer.Serialize(body);
            using var request = new HttpRequestMessage(
                HttpMethod.Post,
                Endpoint + "?action=" + action)
            {
                Content = new StringContent(json, Encoding.UTF8, "application/json")
            };
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _token);

            using var response = await Http.SendAsync(request, cancellationToken);
            var payload = await response.Content.ReadAsStringAsync(cancellationToken);
            if (!response.IsSuccessStatusCode)
                throw new HttpRequestException(
                    $"USB upload {action} gaf {(int)response.StatusCode}: {payload}");

            return JsonDocument.Parse(payload);
        }

        using (await PostJsonAsync("upload-start", new
        {
            device_id = deviceId,
            stick_id = stickId,
            path = relativePath,
            sha256 = sha256
        }))
        {
        }

        const int chunkSize = 32 * 1024;
        var buffer = new byte[chunkSize];
        long offset = 0;

        await using var source = new FileStream(
            filePath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.ReadWrite,
            chunkSize,
            useAsync: true);

        while (true)
        {
            var read = await source.ReadAsync(
                buffer.AsMemory(0, buffer.Length),
                cancellationToken);

            if (read <= 0)
                break;

            var encoded = Convert.ToBase64String(buffer, 0, read);

            using var result = await PostJsonAsync("upload-chunk", new
            {
                device_id = deviceId,
                stick_id = stickId,
                path = relativePath,
                sha256 = sha256,
                offset = offset,
                data = encoded
            });

            offset += read;
        }

        using (await PostJsonAsync("upload-finish", new
        {
            device_id = deviceId,
            stick_id = stickId,
            path = relativePath,
            sha256 = sha256
        }))
        {
        }
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
        const int batchSize = 100;
        var batchTotal = Math.Max(1, (files.Count + batchSize - 1) / batchSize);
        var syncId = Guid.NewGuid().ToString("N");

        for (var batchIndex = 0; batchIndex < batchTotal; batchIndex++)
        {
            var items = files.Skip(batchIndex * batchSize).Take(batchSize).Select(x => new
            {
                path = x.Path,
                title = x.Title,
                artist = x.Artist,
                album = x.Album,
                size = x.Size,
                sha256 = x.Sha256,
                modified = x.Modified
            }).ToList();

            var body = JsonSerializer.Serialize(new
            {
                device_id = deviceId,
                device_name = deviceName,
                stick_id = stickId,
                stick_name = stickName,
                sync_id = syncId,
                batch_index = batchIndex,
                batch_total = batchTotal,
                files = items
            });

            using var request = new HttpRequestMessage(HttpMethod.Post, Endpoint + "?action=sync-batch")
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json")
            };
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _token);
            using var response = await Http.SendAsync(request, cancellationToken);
            var payload = await response.Content.ReadAsStringAsync(cancellationToken);
            if (!response.IsSuccessStatusCode)
                throw new HttpRequestException($"Catalogusbatch {batchIndex + 1}/{batchTotal} gaf {(int)response.StatusCode}: {payload}");
        }
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
        Log("Muziekserver autorisatie gelukt.");
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
            if (IsMacMetadataPath(path, System.IO.Path.GetFileName(path)))
                continue;

            if (!AudioExtensions.Contains(System.IO.Path.GetExtension(path)))
                continue;

            FileInfo info;
            try { info = new FileInfo(path); }
            catch { continue; }

            yield return info;
        }
    }

    private static bool IsMacMetadataPath(string path, string? name = null)
    {
        var clean = (path ?? "").Replace('\\', '/');
        var fileName = string.IsNullOrWhiteSpace(name)
            ? System.IO.Path.GetFileName(clean)
            : name.Trim();

        if (fileName.StartsWith("._", StringComparison.Ordinal))
            return true;
        if (fileName.Equals(".DS_Store", StringComparison.OrdinalIgnoreCase))
            return true;

        return clean.Split('/', StringSplitOptions.RemoveEmptyEntries).Any(segment =>
            segment.Equals("__MACOSX", StringComparison.OrdinalIgnoreCase) ||
            segment.Equals(".Spotlight-V100", StringComparison.OrdinalIgnoreCase) ||
            segment.Equals(".Trashes", StringComparison.OrdinalIgnoreCase) ||
            segment.Equals(".fseventsd", StringComparison.OrdinalIgnoreCase));
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
                ulong fingerprint = 14695981039346656037UL;

                foreach (var file in EnumerateAudioFiles(scanRoot)
                    .OrderBy(x => x.FullName, StringComparer.OrdinalIgnoreCase))
                {
                    count++;
                    bytes += file.Length;
                    var marker =
                        NormalizePath(Path.GetRelativePath(scanRoot, file.FullName)) +
                        "|" + file.Length +
                        "|" + file.LastWriteTimeUtc.Ticks;

                    foreach (var ch in marker)
                    {
                        fingerprint ^= ch;
                        fingerprint *= 1099511628211UL;
                    }
                }

                var identity = VolumeIdentityKey(drive);
                if (string.IsNullOrWhiteSpace(identity))
                    identity = $"media|{drive.TotalSize}|{drive.DriveFormat}|{count}|{bytes}|{fingerprint:x16}";
                parts.Add($"{identity}|{count}|{bytes}|{fingerprint:x16}");
            }
            catch { }
        }

        return string.Join(";", parts.OrderBy(x => x, StringComparer.OrdinalIgnoreCase));
    }

    private static AudioMetadata ReadMetadata(string path)
    {
        try
        {
            using var media = TagLib.File.Create(path);
            var title = (media.Tag.Title ?? "").Trim();
            var artist = (media.Tag.Performers?.FirstOrDefault() ?? "").Trim();
            var album = (media.Tag.Album ?? "").Trim();
            return new AudioMetadata(title, artist, album);
        }
        catch
        {
            return new AudioMetadata("", "", "");
        }
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

    private static string ResolveStickId(
        DriveInfo drive,
        IReadOnlyCollection<CloudUsbMusicStick> catalog)
    {
        var hardwareKey = VolumeIdentityKey(drive);
        var saved = LoadStickIdentityMap();
        if (!string.IsNullOrWhiteSpace(hardwareKey) &&
            saved.TryGetValue(hardwareKey, out var remembered) &&
            !string.IsNullOrWhiteSpace(remembered))
        {
            return remembered;
        }

        var deviceId = SafeId(Environment.MachineName);
        var legacyId = LegacyStickId(drive);

        // Als de huidige naam nog dezelfde is als vroeger, behoud exact de bestaande ID.
        var direct = catalog.FirstOrDefault(x =>
            x.DeviceId.Equals(deviceId, StringComparison.OrdinalIgnoreCase) &&
            x.StickId.Equals(legacyId, StringComparison.OrdinalIgnoreCase));
        if (direct != null)
        {
            RememberStickIdentity(hardwareKey, direct.StickId, saved);
            return direct.StickId;
        }

        // Migratiepad voor een stick waarvan Windows alleen de volumenaam heeft veranderd.
        // Vergelijk relatieve paden + bestandsgrootte met de bestaande Family-catalogus.
        var preferred = Path.Combine(drive.RootDirectory.FullName, "Muziek");
        var scanRoot = Directory.Exists(preferred) ? preferred : drive.RootDirectory.FullName;
        var local = EnumerateAudioFiles(scanRoot)
            .Select(file => NormalizePath(Path.GetRelativePath(scanRoot, file.FullName)) + "|" + file.Length)
            .Take(500)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        CloudUsbMusicStick? best = null;
        var bestScore = 0;
        var bestRatio = 0d;
        foreach (var candidate in catalog.Where(x =>
            x.DeviceId.Equals(deviceId, StringComparison.OrdinalIgnoreCase)))
        {
            var remote = candidate.Files
                .Select(file => NormalizePath(file.Path) + "|" + file.Size)
                .ToHashSet(StringComparer.OrdinalIgnoreCase);
            if (remote.Count == 0 || local.Count == 0) continue;

            var score = local.Count(marker => remote.Contains(marker));
            var ratio = (double)score / Math.Max(1, Math.Min(local.Count, remote.Count));
            if (score > bestScore || (score == bestScore && ratio > bestRatio))
            {
                best = candidate;
                bestScore = score;
                bestRatio = ratio;
            }
        }

        var minimumMatches = Math.Min(10, Math.Max(1, local.Count / 4));
        if (best != null && bestScore >= minimumMatches && bestRatio >= 0.60d)
        {
            Log($"USB-identiteit hersteld: {drive.Name} -> bestaande stick {best.StickId} ({bestScore} matches, {bestRatio:P0}).");
            RememberStickIdentity(hardwareKey, best.StickId, saved);
            return best.StickId;
        }

        // Nieuwe stick: identiteit is gebaseerd op het volume-serienummer en niet op de naam.
        var raw = string.IsNullOrWhiteSpace(hardwareKey)
            ? $"fallback|{drive.TotalSize}|{drive.DriveFormat}"
            : hardwareKey;
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(raw));
        var stableId = Convert.ToHexString(hash).ToLowerInvariant()[..20];
        RememberStickIdentity(hardwareKey, stableId, saved);
        return stableId;
    }

    private static string LegacyStickId(DriveInfo drive)
    {
        var raw = $"{drive.VolumeLabel}|{drive.TotalSize}";
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(raw));
        return Convert.ToHexString(hash).ToLowerInvariant()[..20];
    }

    private static Dictionary<string, string> LoadStickIdentityMap()
    {
        try
        {
            if (!File.Exists(StickIdentityMap))
                return new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

            var json = File.ReadAllText(StickIdentityMap);
            return JsonSerializer.Deserialize<Dictionary<string, string>>(json)
                ?? new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        }
        catch
        {
            return new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        }
    }

    private static void RememberStickIdentity(
        string hardwareKey,
        string stickId,
        Dictionary<string, string> map)
    {
        if (string.IsNullOrWhiteSpace(hardwareKey) || string.IsNullOrWhiteSpace(stickId))
            return;

        try
        {
            map[hardwareKey] = stickId;
            var dir = Path.GetDirectoryName(StickIdentityMap);
            if (!string.IsNullOrWhiteSpace(dir))
                Directory.CreateDirectory(dir);
            File.WriteAllText(StickIdentityMap, JsonSerializer.Serialize(map));
        }
        catch
        {
            // De identity-cache is een optimalisatie; catalogusmatching blijft de fallback.
        }
    }

    private static string VolumeIdentityKey(DriveInfo drive)
    {
        try
        {
            var root = drive.RootDirectory.FullName;
            var volumeName = new StringBuilder(261);
            var fileSystemName = new StringBuilder(261);
            if (GetVolumeInformation(
                root,
                volumeName,
                volumeName.Capacity,
                out var serial,
                out _,
                out _,
                fileSystemName,
                fileSystemName.Capacity))
            {
                return $"volume|{serial:x8}|{drive.TotalSize}|{fileSystemName}";
            }
        }
        catch { }

        return "";
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetVolumeInformation(
        string lpRootPathName,
        StringBuilder lpVolumeNameBuffer,
        int nVolumeNameSize,
        out uint lpVolumeSerialNumber,
        out uint lpMaximumComponentLength,
        out uint lpFileSystemFlags,
        StringBuilder lpFileSystemNameBuffer,
        int nFileSystemNameSize);

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

    private static void Log(string message)
    {
        try
        {
            var dir = Path.GetDirectoryName(DiagnosticLog);
            if (!string.IsNullOrWhiteSpace(dir))
                Directory.CreateDirectory(dir);

            File.AppendAllText(
                DiagnosticLog,
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {message}{Environment.NewLine}");
        }
        catch
        {
            // Diagnostiek mag de muziekfunctie nooit blokkeren.
        }
    }

    private static string NormalizePath(string path) =>
        path.Replace('\\', '/').TrimStart('/');

    private sealed record AudioMetadata(string Title, string Artist, string Album);

    private sealed class LocalManifestFile
    {
        public string Path { get; set; } = "";
        public long Size { get; set; }
        public string Sha256 { get; set; } = "";
        public string Modified { get; set; } = "";
        public string Title { get; set; } = "";
        public string Artist { get; set; } = "";
        public string Album { get; set; } = "";
    }
}
