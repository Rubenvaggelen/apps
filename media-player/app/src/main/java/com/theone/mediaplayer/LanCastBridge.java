package com.theone.mediaplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

final class LanCastBridge {
    interface PlayHandler { void onPlay(String url); }
    interface Callback { void onResult(boolean ok, String message); }

    static final int TV_PORT = 8787;
    static final int MEDIA_PORT = 8788;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean tvRunning;
    private volatile boolean mediaRunning;
    private ServerSocket tvServer;
    private ServerSocket mediaServer;
    private Uri mediaUri;
    private String mediaMime = "application/octet-stream";
    private long mediaLength = -1L;

    LanCastBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    static String localIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    String startTvReceiver(PlayHandler handler) {
        if (tvRunning && tvServer != null && !tvServer.isClosed()) return "";
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(TV_PORT));
            tvServer = server;
            tvRunning = true;
            Thread thread = new Thread(() -> {
                while (tvRunning) {
                    try {
                        Socket socket = server.accept();
                        new Thread(() -> handleControl(socket, handler), "the-one-cast-control").start();
                    } catch (Throwable e) {
                        if (tvRunning) {
                            // Receiver stays best-effort; a later app start retries the bind.
                        }
                    }
                }
            }, "the-one-cast-receiver");
            thread.setDaemon(true);
            thread.start();
            return "";
        } catch (Throwable e) {
            tvRunning = false;
            return e.getClass().getSimpleName();
        }
    }

    private void handleControl(Socket socket, PlayHandler handler) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {
            s.setSoTimeout(5000);
            String first = reader.readLine();
            if (first == null) return;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Headers are intentionally ignored.
            }

            String[] parts = first.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            if (path.startsWith("/play?url=")) {
                String encoded = path.substring("/play?url=".length());
                String url = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
                if (isHttp(url)) {
                    writeText(out, 200, "OK");
                    main.post(() -> handler.onPlay(url));
                } else {
                    writeText(out, 400, "INVALID_URL");
                }
            } else if (path.startsWith("/ping")) {
                writeText(out, 200, "THE_ONE_MEDIA_PLAYER");
            } else {
                writeText(out, 404, "NOT_FOUND");
            }
        } catch (Throwable ignored) {
        }
    }
    void sendUriToTv(String tvHost, Uri uri, Callback callback) {
        if (uri == null) {
            callback.onResult(false, "Kies eerst een video of audiobestand.");
            return;
        }
        String ip = localIpv4();
        if (ip.isEmpty()) {
            callback.onResult(false, "Geen lokaal netwerkadres gevonden.");
            return;
        }
        String error = startMediaServer(uri);
        if (!error.isEmpty()) {
            callback.onResult(false, "Telefoonstream kon niet starten: " + error);
            return;
        }
        String url = "http://" + ip + ":" + MEDIA_PORT + "/media";
        sendUrlToTv(tvHost, url, callback);
    }

    void sendUrlToTv(String tvHost, String url, Callback callback) {
        String host = normalizeHost(tvHost);
        if (host.isEmpty()) {
            callback.onResult(false, "Vul eerst het TV-adres in.");
            return;
        }
        if (!isHttp(url)) {
            callback.onResult(false, "De stream-URL is niet geldig.");
            return;
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.name());
                URL control = new URL("http://" + host + ":" + TV_PORT + "/play?url=" + encoded);
                conn = (HttpURLConnection) control.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                boolean ok = code >= 200 && code < 300;
                main.post(() -> callback.onResult(ok, ok ? "Stream gestart op de TV." : "TV reageerde met HTTP " + code));
            } catch (Throwable e) {
                main.post(() -> callback.onResult(false, "TV niet bereikbaar. Controleer het TV-adres en hetzelfde netwerk."));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "the-one-cast-send").start();
    }
    private String startMediaServer(Uri uri) {
        stopMediaServer();
        mediaUri = uri;
        ContentResolver resolver = context.getContentResolver();
        String type = resolver.getType(uri);
        mediaMime = type == null || type.trim().isEmpty() ? "application/octet-stream" : type;
        mediaLength = queryLength(uri);

        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(MEDIA_PORT));
            mediaServer = server;
            mediaRunning = true;

            Thread thread = new Thread(() -> {
                while (mediaRunning) {
                    try {
                        Socket socket = server.accept();
                        new Thread(() -> serveMedia(socket), "the-one-cast-media-client").start();
                    } catch (Throwable e) {
                        if (mediaRunning) {
                            // A reconnect from ExoPlayer is accepted on the next loop when possible.
                        }
                    }
                }
            }, "the-one-cast-media");
            thread.setDaemon(true);
            thread.start();
            return "";
        } catch (Throwable e) {
            mediaRunning = false;
            return e.getClass().getSimpleName();
        }
    }

    private long queryLength(Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null && pfd.getStatSize() >= 0) return pfd.getStatSize();
        } catch (Throwable ignored) {
        }
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }
    private void serveMedia(Socket socket) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {
            s.setSoTimeout(10000);
            String request = reader.readLine();
            if (request == null) return;

            String range = null;
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                if (header.regionMatches(true, 0, "Range:", 0, 6)) {
                    range = header.substring(6).trim();
                }
            }

            boolean head = request.startsWith("HEAD ");
            if (!request.startsWith("GET ") && !head) {
                writeText(out, 405, "METHOD_NOT_ALLOWED");
                return;
            }

            Uri uri = mediaUri;
            if (uri == null) {
                writeText(out, 404, "NO_MEDIA");
                return;
            }

            long length = mediaLength;
            long start = 0L;
            long end = length > 0 ? length - 1 : -1L;
            boolean partial = false;

            if (range != null && range.startsWith("bytes=") && length > 0) {
                String spec = range.substring(6).split(",")[0].trim();
                String[] bounds = spec.split("-", 2);
                if (!bounds[0].isEmpty()) start = Math.max(0L, Long.parseLong(bounds[0]));
                if (bounds.length > 1 && !bounds[1].isEmpty()) end = Math.min(length - 1, Long.parseLong(bounds[1]));
                if (end < start) end = length - 1;
                partial = true;
            }

            long bodyLength = length > 0 ? Math.max(0L, end - start + 1L) : -1L;
            writeMediaHeaders(out, partial, start, end, length, bodyLength);
            if (head) return;
            try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) return;
                try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                    skipFully(in, start);
                    byte[] buffer = new byte[64 * 1024];
                    long remaining = bodyLength;
                    while (mediaRunning) {
                        int wanted = remaining >= 0 ? (int) Math.min(buffer.length, remaining) : buffer.length;
                        if (wanted <= 0) break;
                        int read = in.read(buffer, 0, wanted);
                        if (read < 0) break;
                        out.write(buffer, 0, read);
                        if (remaining >= 0) remaining -= read;
                    }
                    out.flush();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void writeMediaHeaders(OutputStream out, boolean partial, long start, long end, long total, long bodyLength) throws Exception {
        StringBuilder h = new StringBuilder();
        h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(mediaMime).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        if (bodyLength >= 0) h.append("Content-Length: ").append(bodyLength).append("\r\n");
        if (partial && total > 0) {
            h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
        }
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void skipFully(FileInputStream in, long amount) throws Exception {
        long remaining = amount;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (in.read() >= 0) {
                remaining--;
            } else {
                break;
            }
        }
    }
    String displayName(Uri uri) {
        if (uri == null) return "";
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "Gekozen media" : last;
    }

    void stop() {
        tvRunning = false;
        mediaRunning = false;
        closeServer(tvServer);
        closeServer(mediaServer);
        tvServer = null;
        mediaServer = null;
    }

    private void stopMediaServer() {
        mediaRunning = false;
        closeServer(mediaServer);
        mediaServer = null;
    }

    private void closeServer(ServerSocket server) {
        if (server == null) return;
        try {
            server.close();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isHttp(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase();
        return v.startsWith("http://") || v.startsWith("https://");
    }

    private static String normalizeHost(String value) {
        if (value == null) return "";
        String host = value.trim();
        if (host.startsWith("http://")) host = host.substring(7);
        if (host.startsWith("https://")) host = host.substring(8);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.trim();
    }
    private static void writeText(OutputStream out, int code, String value) throws Exception {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        String status;
        if (code == 200) status = "OK";
        else if (code == 400) status = "Bad Request";
        else if (code == 404) status = "Not Found";
        else status = "Error";

        String headers = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
