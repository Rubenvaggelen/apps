package com.theone.mediaplayer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TheOneCast {
    private static final int DISCOVERY_PORT = 43998;
    private static final int COMMAND_PORT = 43999;
    private static final String DISCOVER = "THE_ONE_DISCOVER";
    private static final String TV_REPLY = "THE_ONE_TV";
    private static final String PLAY = "THE_ONE_PLAY";
    private static final String STOP = "THE_ONE_STOP";

    private TheOneCast() {}

    public interface ReceiverListener {
        void onPlay(String url, String title);
        void onStop();
    }

    public interface DiscoveryCallback {
        void onComplete(List<TvDevice> devices);
    }

    public interface ResultCallback {
        void onComplete(boolean ok, String message);
    }

    public static final class TvDevice {
        public final String name;
        public final InetAddress address;
        public final int commandPort;

        TvDevice(String name, InetAddress address, int commandPort) {
            this.name = name;
            this.address = address;
            this.commandPort = commandPort;
        }

        @Override
        public String toString() {
            return name + " • " + address.getHostAddress();
        }
    }

    public static final class Receiver {
        private final ReceiverListener listener;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private DatagramSocket discoverySocket;
        private ServerSocket commandServer;
        private Thread discoveryThread;
        private Thread commandThread;

        public Receiver(ReceiverListener listener) {
            this.listener = listener;
        }

        public void start() {
            if (!running.compareAndSet(false, true)) return;

            discoveryThread = new Thread(this::runDiscovery, "TheOneCast-Discovery");
            commandThread = new Thread(this::runCommands, "TheOneCast-Commands");
            discoveryThread.start();
            commandThread.start();
        }

        private void runDiscovery() {
            try {
                discoverySocket = new DatagramSocket(DISCOVERY_PORT);
                discoverySocket.setBroadcast(true);
                byte[] buffer = new byte[1024];
                while (running.get()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    discoverySocket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    if (!DISCOVER.equals(msg)) continue;

                    String name = "The One TV";
                    if (Build.MODEL != null && !Build.MODEL.trim().isEmpty()) {
                        name += " • " + Build.MODEL.trim();
                    }
                    String reply = TV_REPLY + "\t" + encode(name) + "\t" + COMMAND_PORT;
                    byte[] data = reply.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket out = new DatagramPacket(data, data.length, packet.getAddress(), packet.getPort());
                    discoverySocket.send(out);
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(discoverySocket);
            }
        }

        private void runCommands() {
            try {
                commandServer = new ServerSocket(COMMAND_PORT);
                while (running.get()) {
                    try (Socket socket = commandServer.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                        String line = reader.readLine();
                        if (line == null) continue;
                        String[] parts = line.split("\t", -1);
                        if (parts.length >= 2 && PLAY.equals(parts[0])) {
                            String url = decode(parts[1]);
                            String title = parts.length >= 3 ? decode(parts[2]) : "Vanaf telefoon";
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                listener.onPlay(url, title);
                            }
                        } else if (STOP.equals(line.trim())) {
                            listener.onStop();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(commandServer);
            }
        }

        public void stop() {
            running.set(false);
            closeQuietly(discoverySocket);
            closeQuietly(commandServer);
            if (discoveryThread != null) discoveryThread.interrupt();
            if (commandThread != null) commandThread.interrupt();
        }
    }

    public static void discover(DiscoveryCallback callback) {
        new Thread(() -> {
            Map<String, TvDevice> found = new LinkedHashMap<>();
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(350);
                byte[] request = DISCOVER.getBytes(StandardCharsets.UTF_8);

                List<InetAddress> targets = broadcastAddresses();
                if (targets.isEmpty()) {
                    targets.add(InetAddress.getByName("255.255.255.255"));
                }
                for (InetAddress target : targets) {
                    try {
                        socket.send(new DatagramPacket(request, request.length, target, DISCOVERY_PORT));
                    } catch (Throwable ignored) {
                    }
                }

                long end = System.currentTimeMillis() + 1800;
                byte[] buf = new byte[1024];
                while (System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        socket.receive(packet);
                        String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                        String[] parts = msg.split("\t", -1);
                        if (parts.length >= 3 && TV_REPLY.equals(parts[0])) {
                            String name = decode(parts[1]);
                            int port;
                            try {
                                port = Integer.parseInt(parts[2]);
                            } catch (Throwable ignored) {
                                port = COMMAND_PORT;
                            }
                            TvDevice device = new TvDevice(name, packet.getAddress(), port);
                            found.put(packet.getAddress().getHostAddress(), device);
                        }
                    } catch (java.net.SocketTimeoutException timeout) {
                        // keep collecting until the total discovery window expires
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(socket);
            }
            callback.onComplete(new ArrayList<>(found.values()));
        }, "TheOneCast-FindTV").start();
    }

    public static void sendPlay(TvDevice tv, String url, String title, ResultCallback callback) {
        new Thread(() -> {
            try (Socket socket = new Socket(tv.address, tv.commandPort);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5000);
                String line = PLAY + "\t" + encode(url) + "\t" + encode(title == null ? "Vanaf telefoon" : title);
                writer.write(line);
                writer.write("\n");
                writer.flush();
                callback.onComplete(true, "Stream gestart op " + tv.name);
            } catch (Throwable e) {
                callback.onComplete(false, "Kon de TV niet bereiken.");
            }
        }, "TheOneCast-Send").start();
    }

    public static void sendStop(TvDevice tv, ResultCallback callback) {
        new Thread(() -> {
            try (Socket socket = new Socket(tv.address, tv.commandPort);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(STOP);
                writer.write("\n");
                writer.flush();
                callback.onComplete(true, "Afspelen op TV gestopt.");
            } catch (Throwable e) {
                callback.onComplete(false, "Kon de TV niet bereiken.");
            }
        }, "TheOneCast-Stop").start();
    }

    public static InetAddress localAddressFor(TvDevice tv) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(tv.address, tv.commandPort);
            InetAddress local = socket.getLocalAddress();
            if (local != null && !local.isAnyLocalAddress()) return local;
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static final class PhoneMediaServer {
        private final Context context;
        private final Uri uri;
        private final String contentType;
        private final long contentLength;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private ServerSocket server;
        private Thread thread;

        public PhoneMediaServer(Context context, Uri uri) {
            this.context = context.getApplicationContext();
            this.uri = uri;
            String type = context.getContentResolver().getType(uri);
            this.contentType = type == null ? "application/octet-stream" : type;
            this.contentLength = resolveSize(context, uri);
        }

        public int start() throws Exception {
            stop();
            server = new ServerSocket(0);
            running.set(true);
            thread = new Thread(this::loop, "TheOneCast-MediaServer");
            thread.start();
            return server.getLocalPort();
        }

        public String urlFor(TvDevice tv) {
            if (server == null) return null;
            InetAddress local = localAddressFor(tv);
            if (local == null) return null;
            String host = local.getHostAddress();
            if (host.contains(":")) host = "[" + host + "]";
            return "http://" + host + ":" + server.getLocalPort() + "/media";
        }

        private void loop() {
            while (running.get()) {
                try {
                    Socket client = server.accept();
                    new Thread(() -> handle(client), "TheOneCast-MediaClient").start();
                } catch (Throwable ignored) {
                    if (!running.get()) break;
                }
            }
        }

        private void handle(Socket client) {
            try (Socket socket = client) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String first = reader.readLine();
                if (first == null) return;
                boolean headOnly = first.startsWith("HEAD ");
                String rangeHeader = null;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && "range".equalsIgnoreCase(line.substring(0, colon).trim())) {
                        rangeHeader = line.substring(colon + 1).trim();
                    }
                }

                long total = contentLength;
                long start = 0;
                long end = total > 0 ? total - 1 : -1;
                boolean partial = false;

                if (rangeHeader != null && rangeHeader.startsWith("bytes=") && total > 0) {
                    String value = rangeHeader.substring(6).split(",")[0].trim();
                    String[] range = value.split("-", 2);
                    if (!range[0].isEmpty()) start = Math.max(0, Long.parseLong(range[0]));
                    if (range.length > 1 && !range[1].isEmpty()) end = Math.min(total - 1, Long.parseLong(range[1]));
                    if (end < start) end = total - 1;
                    partial = true;
                }

                long length = total > 0 ? (end - start + 1) : -1;
                OutputStream out = socket.getOutputStream();
                StringBuilder headers = new StringBuilder();
                headers.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
                headers.append("Content-Type: ").append(contentType).append("\r\n");
                headers.append("Accept-Ranges: bytes\r\n");
                if (length >= 0) headers.append("Content-Length: ").append(length).append("\r\n");
                if (partial && total > 0) {
                    headers.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(total).append("\r\n");
                }
                headers.append("Connection: close\r\n\r\n");
                out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
                out.flush();

                if (headOnly) return;

                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    if (in == null) return;
                    skipFully(in, start);
                    byte[] buffer = new byte[64 * 1024];
                    long remaining = length;
                    while (running.get()) {
                        int max = buffer.length;
                        if (remaining >= 0) {
                            if (remaining <= 0) break;
                            max = (int) Math.min(max, remaining);
                        }
                        int n = in.read(buffer, 0, max);
                        if (n < 0) break;
                        out.write(buffer, 0, n);
                        if (remaining >= 0) remaining -= n;
                    }
                    out.flush();
                }
            } catch (Throwable ignored) {
            }
        }

        public void stop() {
            running.set(false);
            closeQuietly(server);
            server = null;
            if (thread != null) thread.interrupt();
            thread = null;
        }
    }

    public static String displayName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return "Media vanaf telefoon";
    }

    private static long resolveSize(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }

    private static void skipFully(InputStream in, long amount) throws Exception {
        long remaining = amount;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else {
                if (in.read() < 0) break;
                remaining--;
            }
        }
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return result;
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast instanceof Inet4Address) result.add(broadcast);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            result.add(InetAddress.getByName("255.255.255.255"));
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static String encode(String value) {
        if (value == null) value = "";
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String decode(String value) {
        try {
            return new String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void closeQuietly(Object object) {
        if (object == null) return;
        try {
            if (object instanceof DatagramSocket) ((DatagramSocket) object).close();
            else if (object instanceof ServerSocket) ((ServerSocket) object).close();
        } catch (Throwable ignored) {
        }
    }
}
