package com.theone.mediaplayer;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;

public class XtreamMovieCatalogTest {
    private static XtreamConfig source(int port) throws Exception {
        Constructor<XtreamConfig> ctor = XtreamConfig.class.getDeclaredConstructor(
                String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance("http://127.0.0.1:" + port, "testuser", "testpassword");
    }

    private static class MovieServer implements AutoCloseable {
        final ServerSocket socket;
        final Thread thread;
        final boolean fullWorks;
        MovieServer(boolean fullWorks) throws Exception {
            this.fullWorks = fullWorks;
            socket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket conn = socket.accept()) {
                        BufferedReader input = new BufferedReader(new InputStreamReader(
                                conn.getInputStream(), StandardCharsets.UTF_8));
                        String request = input.readLine(), line;
                        if (request == null) continue;
                        while ((line = input.readLine()) != null && !line.isEmpty()) {}
                        String body;
                        if (request.contains("get_vod_categories")) {
                            body = "[{\"category_id\":\"1\"},{\"category_id\":\"2\"}]";
                        } else if (request.contains("category_id=1")) {
                            body = "[{\"stream_id\":101,\"name\":\"Your Mother (2026)\",\"container_extension\":\"mkv\"}]";
                        } else if (request.contains("category_id=2")) {
                            body = "[{\"stream_id\":102,\"name\":\"Another Film\",\"container_extension\":\"mp4\"}]";
                        } else if (fullWorks) {
                            body = "[{\"stream_id\":101,\"name\":\"Your Mother (2026)\",\"container_extension\":\"mkv\"}," +
                                    "{\"stream_id\":102,\"name\":\"Another Film\",\"container_extension\":\"mp4\"}]";
                        } else {
                            body = "[]";
                        }
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        OutputStream out = conn.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + bytes.length +
                                "\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                        out.write(bytes);
                        out.flush();
                    } catch (Exception ignored) {}
                }
            }, "movie-catalog-test");
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return socket.getLocalPort(); }
        public void close() throws Exception { socket.close(); thread.join(1000); }
    }

    @Test public void filmSearchPreservesExtensionsAndRecoversCategories() throws Exception {
        try (MovieServer server = new MovieServer(false)) {
            XtreamConfig config = source(server.port());
            List<String> names = Collections.synchronizedList(new ArrayList<>());
            List<String> extensions = Collections.synchronizedList(new ArrayList<>());
            boolean complete = config.streamMovieCatalog(25, (batch, second) -> {
                for (int i = 0; i < batch.length(); i++) {
                    JSONObject row = batch.optJSONObject(i);
                    if (row != null) {
                        names.add(row.optString("name"));
                        extensions.add(row.optString("container_extension"));
                    }
                }
            });
            assertTrue("Category recovery must finish", complete);
            assertEquals(Arrays.asList("Your Mother (2026)", "Another Film"), names);
            assertEquals(Arrays.asList("mkv", "mp4"), extensions);
        }
    }

    @Test public void twoSourcesRetainProviderIds() throws Exception {
        try (MovieServer one = new MovieServer(true);
             MovieServer two = new MovieServer(true)) {
            XtreamConfig config = source(one.port());
            Field secondary = XtreamConfig.class.getDeclaredField("secondary");
            secondary.setAccessible(true);
            secondary.set(config, source(two.port()));
            List<String> ids = Collections.synchronizedList(new ArrayList<>());
            boolean complete = config.streamMovieCatalog(25, (rows, second) -> {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject item = rows.optJSONObject(i);
                    if (item != null) ids.add(item.optString("stream_id"));
                }
            });
            assertTrue(complete);
            assertEquals(4, ids.size());
            assertTrue(ids.contains("101"));
            assertTrue(ids.contains("source2:101"));
            assertTrue(ids.contains("source2:102"));
        }
    }

    @Test public void filmCacheRemainsUsableWhenProvidersDisconnect() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("film-catalog-cache").toFile();
        try (MovieServer server = new MovieServer(true)) {
            XtreamConfig config = source(server.port());
            Field cache = XtreamConfig.class.getDeclaredField("catalogCacheDir");
            cache.setAccessible(true);
            cache.set(config, dir);
            List<String> titles = Collections.synchronizedList(new ArrayList<>());
            assertTrue(config.streamMovieCatalog(25, (rows, second) -> {
                for (int i = 0; i < rows.length(); i++) titles.add(rows.optJSONObject(i).optString("name"));
            }));
            assertEquals(2, titles.size());
            server.close();
            titles.clear();
            assertTrue("The complete cached catalog must survive offline source",
                    config.streamMovieCatalog(25, (rows, second) -> {
                        for (int i = 0; i < rows.length(); i++)
                            titles.add(rows.optJSONObject(i).optString("name"));
                    }));
            assertEquals(2, titles.size());
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
            dir.delete();
        }
    }
}
