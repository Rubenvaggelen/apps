using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace TheOneMain.Windows;

public sealed record SupremacyMix(string Title, string Url, string Genre);

public static class SupremacyMusicService
{
    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromSeconds(25)
    };

    public static async Task<List<SupremacyMix>> LoadAsync()
    {
        var map = new Dictionary<string, SupremacyMix>(StringComparer.OrdinalIgnoreCase);

        try { await LoadOfficialAsync(map); } catch { }
        try { await LoadHearThisAsync(map); } catch { }

        return map.Values
            .OrderBy(x => x.Genre, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(x => x.Title, StringComparer.CurrentCultureIgnoreCase)
            .ToList();
    }

    private static async Task LoadHearThisAsync(Dictionary<string, SupremacyMix> target)
    {
        for (var page = 1; page <= 5; page++)
        {
            var json = await Http.GetStringAsync(
                $"https://api-v2.hearthis.at/supremacysounds/?type=tracks&count=100&page={page}");
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.GetArrayLength() == 0) break;

            foreach (var item in doc.RootElement.EnumerateArray())
            {
                var title = item.TryGetProperty("title", out var t) ? (t.GetString() ?? "").Trim() : "";
                var url = item.TryGetProperty("stream_url", out var u) ? (u.GetString() ?? "").Trim() : "";
                var rawGenre = item.TryGetProperty("genre", out var g) ? (g.GetString() ?? "") : "";
                if (title.Length == 0 || !url.StartsWith("http", StringComparison.OrdinalIgnoreCase)) continue;

                var genre = NormalizeGenre(rawGenre, title);
                var key = Key(title);
                if (!target.TryGetValue(key, out var existing) || existing.Genre == "Overig")
                    target[key] = new SupremacyMix(title, url, genre);
            }

            if (doc.RootElement.GetArrayLength() < 100) break;
        }
    }

    private static async Task LoadOfficialAsync(Dictionary<string, SupremacyMix> target)
    {
        var html = await Http.GetStringAsync("https://supremacysounds.com/downloads/");
        var regex = new Regex(
            "<a[^>]+href=[\"']([^\"']*files\\.supremacysounds\\.com[^\"']*)[\"'][^>]*>(.*?)</a>",
            RegexOptions.IgnoreCase | RegexOptions.Singleline);

        foreach (Match match in regex.Matches(html))
        {
            var url = WebUtility.HtmlDecode(match.Groups[1].Value).Trim();
            var title = Regex.Replace(match.Groups[2].Value, "<.*?>", " ");
            title = WebUtility.HtmlDecode(Regex.Replace(title, "\\s+", " ")).Trim();
            if (title.Length == 0 || !url.StartsWith("http", StringComparison.OrdinalIgnoreCase)) continue;

            var genre = InferGenre(title);
            var key = Key(title);
            if (!target.ContainsKey(key))
                target[key] = new SupremacyMix(title, url, genre);
        }
    }

    private static string NormalizeGenre(string raw, string title)
    {
        var original = (raw ?? "").Trim();
        var value = original.ToLowerInvariant();

        if (value.Contains("dancehall")) return "Dancehall";
        if (value.Contains("reggae")) return "Reggae";
        if (value.Contains("soca")) return "Soca";
        if (value.Contains("afro")) return "Afrobeats";
        if (value.Contains("hip") || value.Contains("rap")) return "Hip-Hop / R&B";
        if (value.Contains("r&b") || value.Contains("soul")) return "Hip-Hop / R&B";
        if (value.Contains("pop")) return "Pop";
        if (value.Contains("house") || value.Contains("dance") || value.Contains("edm")) return "Dance / House";
        if (value.Contains("world")) return InferGenre(title);
        if (value.Length > 0) return original;
        return InferGenre(title);
    }

    private static string InferGenre(string title)
    {
        var t = (title ?? "").ToLowerInvariant();
        if (Regex.IsMatch(t, @"\bsoca\b|trinidad|carnival|power soca|groovy soca")) return "Soca";
        if (Regex.IsMatch(t, @"dancehall|bashment|jamaica|jamaican")) return "Dancehall";
        if (Regex.IsMatch(t, @"\breggae\b|lovers rock|roots")) return "Reggae";
        if (Regex.IsMatch(t, @"afrobeats?|afrobeat|amapiano|uganda|ugandan|kenya|kenyan|ghana|nigeria|naija")) return "Afrobeats";
        if (Regex.IsMatch(t, @"hip.?hop|rap|r&b|rnb|slow jam|soul")) return "Hip-Hop / R&B";
        if (Regex.IsMatch(t, @"house|edm|dance mix|club bangers")) return "Dance / House";
        if (Regex.IsMatch(t, @"\bpop\b|80s|90s|2000s")) return "Pop";
        return "Overig";
    }

    private static string Key(string title) =>
        Regex.Replace((title ?? "").ToLowerInvariant(), @"[^a-z0-9]+", " ").Trim();
}
