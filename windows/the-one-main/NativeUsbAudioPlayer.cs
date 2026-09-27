using System.Windows.Media;

namespace TheOneMain.Windows;

public static class NativeUsbAudioPlayer
{
    private static readonly MediaPlayer Player = new();
    private static readonly object Gate = new();
    private static List<string> _queue = new();
    private static int _index = -1;
    private static bool _isPlaying;

    static NativeUsbAudioPlayer()
    {
        Player.MediaEnded += (_, _) => Next();
        Player.MediaFailed += (_, e) =>
        {
            _isPlaying = false;
            LastError = e.ErrorException?.Message ?? "Afspelen mislukt";
            PlaybackFailed?.Invoke(LastError);
        };
    }

    public static event Action<string>? TrackChanged;
    public static event Action<string>? PlaybackFailed;

    public static string LastError { get; private set; } = "";

    public static void PlayQueue(IEnumerable<string> urls, int startIndex = 0)
    {
        var list = urls
            .Where(x => !string.IsNullOrWhiteSpace(x))
            .Where(x => Uri.TryCreate(x, UriKind.Absolute, out _))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();

        if (list.Count == 0)
            throw new InvalidOperationException("Geen geldige USB-streams gevonden.");

        lock (Gate)
        {
            _queue = list;
            _index = Math.Clamp(startIndex, 0, _queue.Count - 1);
            OpenCurrent();
        }
    }

    public static void Toggle()
    {
        lock (Gate)
        {
            if (_index < 0) return;

            if (_isPlaying)
            {
                Player.Pause();
                _isPlaying = false;
            }
            else
            {
                Player.Play();
                _isPlaying = true;
            }
        }
    }

    public static void Stop()
    {
        lock (Gate)
        {
            Player.Stop();
            Player.Close();
            _queue.Clear();
            _index = -1;
            _isPlaying = false;
        }
    }

    public static void Next()
    {
        lock (Gate)
        {
            if (_index + 1 >= _queue.Count) return;
            _index++;
            OpenCurrent();
        }
    }

    public static void Previous()
    {
        lock (Gate)
        {
            if (_queue.Count == 0) return;
            _index = Math.Max(0, _index - 1);
            OpenCurrent();
        }
    }

    private static void OpenCurrent()
    {
        if (_index < 0 || _index >= _queue.Count) return;

        LastError = "";
        var url = _queue[_index];
        Player.Close();
        Player.Open(new Uri(url, UriKind.Absolute));
        Player.Play();
        _isPlaying = true;
        TrackChanged?.Invoke(url);
    }
}
