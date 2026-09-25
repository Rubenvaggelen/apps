using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;

namespace TheOneMain.Windows;

public sealed record YouTubeMusicResult(
    string VideoId,
    string Title,
    string Channel,
    string ThumbnailUrl);

public static class YouTubeMusicService
{
    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromSeconds(15)
    };

    private static CoreWebView2Environment? _environment;
    private static readonly SemaphoreSlim EnvironmentLock = new(1, 1);

    public static async Task<List<YouTubeMusicResult>> SearchAsync(string query)
    {
        if (string.IsNullOrWhiteSpace(BuildSecrets.YouTubeApiKey))
            throw new InvalidOperationException("YouTube is nog niet gekoppeld aan deze Windows-build.");

        var url =
            "https://www.googleapis.com/youtube/v3/search" +
            "?part=snippet&type=video&videoCategoryId=10&maxResults=12" +
            "&videoEmbeddable=true&videoSyndicated=true" +
            "&safeSearch=moderate&q=" + Uri.EscapeDataString(query.Trim()) +
            "&key=" + Uri.EscapeDataString(BuildSecrets.YouTubeApiKey);

        using var response = await Http.GetAsync(url);
        var json = await response.Content.ReadAsStringAsync();

        using var doc = JsonDocument.Parse(json);
        if (!response.IsSuccessStatusCode)
        {
            var message = doc.RootElement.TryGetProperty("error", out var error) &&
                          error.TryGetProperty("message", out var msg)
                ? msg.GetString()
                : "YouTube zoekopdracht mislukt.";
            throw new InvalidOperationException(message);
        }

        var results = new List<YouTubeMusicResult>();
        if (!doc.RootElement.TryGetProperty("items", out var items))
            return results;

        foreach (var item in items.EnumerateArray())
        {
            if (!item.TryGetProperty("id", out var id) ||
                !id.TryGetProperty("videoId", out var videoIdElement))
                continue;

            var videoId = videoIdElement.GetString() ?? "";
            if (videoId.Length == 0) continue;

            if (!item.TryGetProperty("snippet", out var snippet))
                continue;

            var title = HtmlDecode(snippet.GetProperty("title").GetString() ?? query);
            var channel = HtmlDecode(snippet.GetProperty("channelTitle").GetString() ?? "YouTube");

            string thumbnail = "";
            if (snippet.TryGetProperty("thumbnails", out var thumbnails))
            {
                if (thumbnails.TryGetProperty("medium", out var medium) &&
                    medium.TryGetProperty("url", out var mediumUrl))
                    thumbnail = mediumUrl.GetString() ?? "";
                else if (thumbnails.TryGetProperty("default", out var def) &&
                         def.TryGetProperty("url", out var defaultUrl))
                    thumbnail = defaultUrl.GetString() ?? "";
            }

            results.Add(new YouTubeMusicResult(videoId, title, channel, thumbnail));
        }

        return results;
    }

    public static WebView2 CreatePlayer() => new()
    {
        HorizontalAlignment = HorizontalAlignment.Stretch,
        VerticalAlignment = VerticalAlignment.Stretch,
        DefaultBackgroundColor = System.Drawing.Color.FromArgb(5, 7, 11)
    };

    public static async Task InitializeAsync(WebView2 web)
    {
        if (web.CoreWebView2 != null) return;

        var environment = await GetEnvironmentAsync();
        await web.EnsureCoreWebView2Async(environment);

        web.CoreWebView2.Settings.AreDevToolsEnabled = false;
        web.CoreWebView2.Settings.IsStatusBarEnabled = false;
        web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;

        var playerFolder = EnsurePlayerFiles();
        web.CoreWebView2.SetVirtualHostNameToFolderMapping(
            "theone-music.local",
            playerFolder,
            CoreWebView2HostResourceAccessKind.Allow);

        web.Source = new Uri("https://theone-music.local/index.html");
    }

    public static async Task PlayAsync(WebView2 web, string videoId)
    {
        await InitializeAsync(web);
        if (string.IsNullOrWhiteSpace(videoId)) return;

        var safeId = new string(videoId.Where(c =>
            char.IsLetterOrDigit(c) || c == '-' || c == '_').ToArray());

        if (safeId.Length == 0) return;

        // YouTube error 153 ontstaat wanneer een embed zonder geldige verwijzer/origin
        // wordt geopend. De lokale virtuele HTTPS-host geeft de speler wél een echte
        // origin en referer, terwijl alles in The One Window blijft.
        web.Source = new Uri(
            "https://theone-music.local/player.html?v=" +
            Uri.EscapeDataString(safeId));
    }

    private static string EnsurePlayerFiles()
    {
        var folder = Path.Combine(AppStore.BaseDirectory, "youtube-music-player");
        Directory.CreateDirectory(folder);

        File.WriteAllText(
            Path.Combine(folder, "index.html"),
            """
<!doctype html>
<html lang="nl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{height:100%;margin:0;background:#05070B;color:#9AA6B2;font-family:Segoe UI,Arial,sans-serif}
body{display:flex;align-items:center;justify-content:center}
.wrap{text-align:center}.note{font-size:18px;margin-top:12px}.icon{font-size:46px;color:#20B8FF}
</style>
</head>
<body><div class="wrap"><div class="icon">♫</div><div class="note">Kies een nummer uit de zoekresultaten</div></div></body>
</html>
""");

        File.WriteAllText(
            Path.Combine(folder, "player.html"),
            """
<!doctype html>
<html lang="nl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{width:100%;height:100%;margin:0;overflow:hidden;background:#05070B}
iframe{display:block;width:100%;height:100%;border:0;background:#05070B}
</style>
</head>
<body>
<iframe id="player"
  title="YouTube muziekspeler"
  allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
  allowfullscreen
  referrerpolicy="strict-origin-when-cross-origin"></iframe>
<script>
const id = new URLSearchParams(location.search).get('v') || '';
const safe = /^[A-Za-z0-9_-]+$/.test(id) ? id : '';
if (safe) {
  const origin = encodeURIComponent(location.origin);
  document.getElementById('player').src =
    'https://www.youtube.com/embed/' + encodeURIComponent(safe) +
    '?autoplay=1&rel=0&modestbranding=1&playsinline=1&origin=' + origin;
}
</script>
</body>
</html>
""");

        return folder;
    }

    public static Image CreateThumbnail(string url)
    {
        var image = new Image
        {
            Width = 100,
            Height = 58,
            Stretch = Stretch.UniformToFill,
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center
        };

        if (string.IsNullOrWhiteSpace(url))
            return image;

        try
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.UriSource = new Uri(url);
            bitmap.EndInit();
            bitmap.Freeze();
            image.Source = bitmap;
        }
        catch { }

        return image;
    }

    private static async Task<CoreWebView2Environment> GetEnvironmentAsync()
    {
        if (_environment != null) return _environment;

        await EnvironmentLock.WaitAsync();
        try
        {
            if (_environment != null) return _environment;

            var userData = Path.Combine(AppStore.BaseDirectory, "youtube-music-webview");
            Directory.CreateDirectory(userData);
            _environment = await CoreWebView2Environment.CreateAsync(
                browserExecutableFolder: null,
                userDataFolder: userData);
            return _environment;
        }
        finally
        {
            EnvironmentLock.Release();
        }
    }

    private static string HtmlDecode(string value) =>
        System.Net.WebUtility.HtmlDecode(value ?? "");
}
