using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace TheOneMain.Windows;

public static class CustomTileIconService
{
    private const uint ShgfiIcon = 0x000000100;
    private const uint ShgfiLargeIcon = 0x000000000;
    private static readonly HttpClient Http = CreateHttp();
    private static readonly string CacheDirectory =
        Path.Combine(AppStore.BaseDirectory, "tile-icons");

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ShFileInfo
    {
        public IntPtr hIcon;
        public int iIcon;
        public uint dwAttributes;

        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)]
        public string szDisplayName;

        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)]
        public string szTypeName;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr SHGetFileInfo(
        string pszPath,
        uint dwFileAttributes,
        ref ShFileInfo psfi,
        uint cbFileInfo,
        uint uFlags);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr hIcon);

    public static ImageSource? TryGetWindowsIcon(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
            return null;

        try
        {
            var info = new ShFileInfo();
            var result = SHGetFileInfo(
                path,
                0,
                ref info,
                (uint)Marshal.SizeOf<ShFileInfo>(),
                ShgfiIcon | ShgfiLargeIcon);

            if (result == IntPtr.Zero || info.hIcon == IntPtr.Zero)
                return null;

            try
            {
                var source = Imaging.CreateBitmapSourceFromHIcon(
                    info.hIcon,
                    Int32Rect.Empty,
                    BitmapSizeOptions.FromWidthAndHeight(128, 128));
                source.Freeze();
                return source;
            }
            finally
            {
                DestroyIcon(info.hIcon);
            }
        }
        catch
        {
            return null;
        }
    }

    public static async Task<ImageSource?> GetWebsiteIconAsync(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
            return null;

        Directory.CreateDirectory(CacheDirectory);
        var key = Convert.ToHexString(
            SHA256.HashData(Encoding.UTF8.GetBytes(uri.Host.ToLowerInvariant())));
        var cachePath = Path.Combine(CacheDirectory, key + ".img");

        if (File.Exists(cachePath))
        {
            try
            {
                return LoadBitmap(await File.ReadAllBytesAsync(cachePath));
            }
            catch
            {
                try { File.Delete(cachePath); } catch { }
            }
        }

        byte[]? bytes = null;

        // Eerst de website zelf, daarna de betrouwbare favicon-resolver.
        try
        {
            var rootIcon = new Uri(uri.GetLeftPart(UriPartial.Authority).TrimEnd('/') + "/favicon.ico");
            bytes = await DownloadBytesAsync(rootIcon);
        }
        catch { }

        if (bytes == null || bytes.Length < 32)
        {
            try
            {
                var resolver =
                    "https://www.google.com/s2/favicons?domain_url=" +
                    Uri.EscapeDataString(uri.GetLeftPart(UriPartial.Authority)) +
                    "&sz=128";
                bytes = await DownloadBytesAsync(new Uri(resolver));
            }
            catch { }
        }

        if (bytes == null || bytes.Length < 32)
            return null;

        try
        {
            await File.WriteAllBytesAsync(cachePath, bytes);
        }
        catch { }

        return LoadBitmap(bytes);
    }

    private static async Task<byte[]?> DownloadBytesAsync(Uri uri)
    {
        using var response = await Http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead);
        if (!response.IsSuccessStatusCode)
            return null;

        var contentType = response.Content.Headers.ContentType?.MediaType ?? "";
        if (!contentType.StartsWith("image/", StringComparison.OrdinalIgnoreCase) &&
            !uri.AbsolutePath.EndsWith(".ico", StringComparison.OrdinalIgnoreCase))
            return null;

        var bytes = await response.Content.ReadAsByteArrayAsync();
        return bytes.Length <= 2_000_000 ? bytes : null;
    }

    private static ImageSource? LoadBitmap(byte[] bytes)
    {
        try
        {
            using var stream = new MemoryStream(bytes);
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.CreateOptions = BitmapCreateOptions.PreservePixelFormat;
            image.StreamSource = stream;
            image.EndInit();
            image.Freeze();
            return image;
        }
        catch
        {
            return null;
        }
    }

    private static HttpClient CreateHttp()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromSeconds(4) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("TheOneWindow/1.0");
        return client;
    }
}
