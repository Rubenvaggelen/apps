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

        // Houd links die normaal een nieuw browservenster openen ook binnen
        // het Muziek-paneel van The One Window.
        web.CoreWebView2.NewWindowRequested += (_, e) =>
        {
            try
            {
                if (Uri.TryCreate(e.Uri, UriKind.Absolute, out var uri))
                    web.Source = uri;
            }
            catch { }
            e.Handled = true;
        };

        var playerFolder = EnsurePlayerFiles();
        web.CoreWebView2.SetVirtualHostNameToFolderMapping(
            "theone-music.local",
            playerFolder,
            CoreWebView2HostResourceAccessKind.Allow);

        web.Source = new Uri("https://theone-music.local/index.html");
    }

    public static async Task OpenSpotifySearchAsync(WebView2 web, string query)
    {
        await InitializeAsync(web);
        var value = query?.Trim() ?? "";
        if (value.Length == 0) return;

        // Spotify opent als volledige webplayer in dezelfde WebView2.
        // Daardoor blijven zoeken, inloggen en afspelen binnen The One Window
        // in plaats van in Chrome of een los Spotify-venster.
        web.Source = new Uri(
            "https://open.spotify.com/search/" +
            Uri.EscapeDataString(value));
    }

    public static Task PlayAsync(WebView2 web, string videoId) =>
        PlayQueueAsync(web, new[] { videoId });

    public static async Task PlayQueueAsync(WebView2 web, IEnumerable<string> videoIds)
    {
        await InitializeAsync(web);

        var queue = videoIds
            .Where(x => !string.IsNullOrWhiteSpace(x))
            .Select(x => new string(x.Where(c =>
                char.IsLetterOrDigit(c) || c == '-' || c == '_').ToArray()))
            .Where(x => x.Length > 0)
            .Distinct(StringComparer.Ordinal)
            .Take(25)
            .ToList();

        if (queue.Count == 0) return;

        // De geselecteerde video start direct. Daarna gebruikt de interne YouTube
        // playlist automatisch de volgende zoekresultaten zonder terug te gaan
        // naar de resultatenlijst of een nieuw venster te openen.
        var encodedQueue = Uri.EscapeDataString(string.Join(",", queue));
        web.Source = new Uri(
            "https://theone-music.local/player.html?q=" + encodedQueue);
    }

    public static async Task PlayAudioQueueAsync(WebView2 web, IEnumerable<string> urls)
    {
        await InitializeAsync(web);

        var queue = urls
            .Where(x => !string.IsNullOrWhiteSpace(x))
            .Where(x => Uri.TryCreate(x, UriKind.Absolute, out _))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(50)
            .ToList();

        if (queue.Count == 0) return;

        var json = JsonSerializer.Serialize(queue);
        var html = """
<!doctype html>
<html lang="nl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{width:100%;height:100%;margin:0;background:#05070B;color:#f3f8fc;font-family:Segoe UI,Arial,sans-serif}
body{display:flex;align-items:center;justify-content:center}
.card{width:min(92%,760px);padding:28px;border:1px solid #174963;border-radius:18px;background:#091018;box-shadow:0 0 28px rgba(32,184,255,.18)}
.logo{font-size:42px;color:#20B8FF;text-align:center;margin-bottom:18px}
.label{text-align:center;color:#9AA6B2;margin-bottom:18px}
audio{width:100%}
</style>
</head>
<body>
<div class="card">
  <div class="logo">♫</div>
  <div class="label">Supremacy mixen • The One Player</div>
  <audio id="audio" controls autoplay></audio>
</div>
<script>
const queue = __QUEUE__;
let index = 0;
const audio = document.getElementById('audio');
function load(i) {
  if (!queue.length) return;
  index = Math.max(0, Math.min(i, queue.length - 1));
  audio.src = queue[index];
  audio.play().catch(()=>{});
}
audio.addEventListener('ended', () => {
  if (index + 1 < queue.length) load(index + 1);
});
window.theOneToggle = () => audio.paused ? audio.play() : audio.pause();
window.theOneNext = () => { if (index + 1 < queue.length) load(index + 1); };
load(0);
</script>
</body>
</html>
""".Replace("__QUEUE__", json);

        web.NavigateToString(html);
    }

    public static async Task TogglePlayPauseAsync(WebView2 web)
    {
        await InitializeAsync(web);
        await web.ExecuteScriptAsync("window.theOneToggle && window.theOneToggle()");
    }

    public static async Task NextAsync(WebView2 web)
    {
        await InitializeAsync(web);
        await web.ExecuteScriptAsync("window.theOneNext && window.theOneNext()");
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
<div id="player"></div>
<script>
const raw = new URLSearchParams(location.search).get('q') || '';
const queue = raw
  .split(',')
  .map(v => v.trim())
  .filter(v => /^[A-Za-z0-9_-]+$/.test(v))
  .slice(0, 25);

function onYouTubeIframeAPIReady() {
  if (!queue.length) return;

  window.theOnePlayer = new YT.Player('player', {
    width: '100%',
    height: '100%',
    playerVars: {
      autoplay: 1,
      rel: 0,
      modestbranding: 1,
      playsinline: 1,
      origin: location.origin
    },
    events: {
      onReady: event => {
        event.target.loadPlaylist({
          playlist: queue,
          index: 0,
          startSeconds: 0
        });
      }
    }
  });

  window.theOneToggle = () => {
    const p = window.theOnePlayer;
    if (!p || !p.getPlayerState) return;
    const state = p.getPlayerState();
    if (state === YT.PlayerState.PLAYING) p.pauseVideo();
    else p.playVideo();
  };

  window.theOneNext = () => {
    const p = window.theOnePlayer;
    if (p && p.nextVideo) p.nextVideo();
  };
}

const api = document.createElement('script');
api.src = 'https://www.youtube.com/iframe_api';
document.head.appendChild(api);
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
