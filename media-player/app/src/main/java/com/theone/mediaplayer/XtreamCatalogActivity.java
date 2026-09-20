package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class XtreamCatalogActivity extends Activity {
    private static final int BLUE = Color.rgb(32, 184, 255);
    private static final int BG = Color.rgb(5, 7, 11);
    private static final int PANEL = Color.rgb(17, 23, 34);
    private static final int MUTED = Color.rgb(154, 166, 178);

    private XtreamConfig config;
    private String mode;
    private LinearLayout root;
    private LinearLayout content;

    private final List<XtreamItem> categoryItems = new ArrayList<>();
    private final List<XtreamItem> globalItems = new ArrayList<>();
    private boolean globalLoaded = false;
    private final List<Category> categoryCache = new ArrayList<>();
    private volatile boolean categoriesLoading = false;
    private final java.util.Map<String, List<XtreamItem>> categoryItemCache = new java.util.HashMap<>();
    private final java.util.Map<String, List<XtreamItem>> seriesEpisodeCache = new java.util.HashMap<>();
    private final ExecutorService imagePool = Executors.newFixedThreadPool(4);
    private Category currentCategory = null;
    private final List<XtreamItem> currentCategoryItems = new ArrayList<>();
    private String currentView = "categories";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = XtreamConfig.load(this);
        mode = getIntent().getStringExtra("mode");
        if (!"movie".equals(mode) && !"series".equals(mode)) mode = "live";
        renderShell();
        if (!config.isConfigured()) {
            showMessage("Privé Xtream-bron ontbreekt in deze APK.");
            return;
        }
        loadCategories();
    }

    private void renderShell() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PremiumUi.BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(28));
        scroll.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = PremiumUi.brandHeader(this, heading());
        top.addView(header);

        Button back = PremiumUi.chipButton(this, "←  1 stap terug");
        back.setOnClickListener(v -> goBackOneStep());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        backLp.topMargin = dp(8);
        top.addView(back, backLp);
        root.addView(top);

        TextView sub = text(
                "Rechtstreeks uit jouw privé Xtream-bron.",
                15,
                MUTED,
                false
        );
        sub.setPadding(0, dp(10), 0, dp(14));
        root.addView(sub);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);

        setContentView(scroll);
    }

    private String heading() {
        if ("movie".equals(mode)) return "Films";
        if ("series".equals(mode)) return "Series";
        return "Live TV";
    }

    private void loadCategories() {
        List<Category> available = categoryCache.isEmpty()
                ? loadCachedCategories()
                : new ArrayList<>(categoryCache);

        if (!available.isEmpty()) {
            categoryCache.clear();
            categoryCache.addAll(available);
            renderCategories(new ArrayList<>(categoryCache));
        } else {
            showLoading("Categorieën laden…");
        }

        if (categoriesLoading) return;
        categoriesLoading = true;

        String action = "live".equals(mode)
                ? "get_live_categories"
                : ("movie".equals(mode) ? "get_vod_categories" : "get_series_categories");

        new Thread(() -> {
            List<Category> fresh = new ArrayList<>();

            for (int attempt = 0; attempt < 2 && fresh.isEmpty(); attempt++) {
                try {
                    JSONArray arr = new JSONArray(get(config.api(action)));

                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;

                        String id = o.optString("category_id", "");
                        String name = o.optString("category_name", "Categorie");
                        if (!id.isEmpty()) fresh.add(new Category(id, name));
                    }
                } catch (Throwable ignored) {
                }

                if (fresh.isEmpty() && attempt == 0) {
                    try {
                        Thread.sleep(450);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            List<Category> result = new ArrayList<>(fresh);
            runOnUiThread(() -> {
                categoriesLoading = false;

                if (!result.isEmpty()) {
                    categoryCache.clear();
                    categoryCache.addAll(result);
                    saveCachedCategories(result);
                    renderCategories(new ArrayList<>(result));
                    return;
                }

                if (!categoryCache.isEmpty()) {
                    renderCategories(new ArrayList<>(categoryCache));
                    return;
                }

                showCategoriesRetry();
            });
        }).start();
    }

    private String categoryCacheKey() {
        return "xtream_categories_" + mode;
    }

    private List<Category> loadCachedCategories() {
        List<Category> out = new ArrayList<>();

        try {
            SharedPreferences prefs = getSharedPreferences("media_player", Context.MODE_PRIVATE);
            String raw = prefs.getString(categoryCacheKey(), "");
            if (raw == null || raw.trim().isEmpty()) return out;

            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;

                String id = o.optString("id", "");
                String name = o.optString("name", "Categorie");
                if (!id.isEmpty()) out.add(new Category(id, name));
            }
        } catch (Throwable ignored) {
        }

        return out;
    }

    private void saveCachedCategories(List<Category> categories) {
        try {
            JSONArray arr = new JSONArray();
            for (Category category : categories) {
                JSONObject o = new JSONObject();
                o.put("id", category.id);
                o.put("name", category.name);
                arr.put(o);
            }

            getSharedPreferences("media_player", Context.MODE_PRIVATE)
                    .edit()
                    .putString(categoryCacheKey(), arr.toString())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void showCategoriesRetry() {
        content.removeAllViews();

        TextView message = text(
                "Categorieën konden niet worden geladen. Probeer opnieuw.",
                17,
                Color.WHITE,
                false
        );
        message.setPadding(0, dp(12), 0, dp(12));
        content.addView(message);

        Button retry = button("Opnieuw laden");
        retry.setOnClickListener(v -> loadCategories());
        content.addView(retry);
    }

    private void renderCategories(List<Category> categories) {
        currentView = "categories";
        currentCategory = null;
        currentCategoryItems.clear();
        content.removeAllViews();

        if (!"live".equals(mode)) {
            addGlobalSearch();
        }

        TextView label = text("Categorieën", 21, BLUE, true);
        label.setPadding(0, "live".equals(mode) ? 0 : dp(12), 0, dp(10));
        content.addView(label);

        if (categories.isEmpty()) {
            content.addView(text("Geen categorieën gevonden.", 16, MUTED, false));
            return;
        }

        for (Category category : categories) {
            Button b = PremiumUi.chipButton(this, category.name);
            b.setOnClickListener(v -> loadItems(category));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = dp(8);
            content.addView(b, lp);
        }
    }

    private void addGlobalSearch() {
        TextView title = text(
                "movie".equals(mode) ? "Zoek in alle films" : "Zoek in alle series",
                21,
                Color.WHITE,
                true
        );
        title.setPadding(0, 0, 0, dp(8));
        content.addView(title);

        EditText search = PremiumUi.searchField(this, "Typ een titel…");
        content.addView(search);

        Button find = button("Zoeken in volledige bibliotheek");
        find.setOnClickListener(v -> {
            String q = search.getText().toString().trim();
            if (q.isEmpty()) return;
            searchGlobal(q);
        });

        LinearLayout.LayoutParams findLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        findLp.topMargin = dp(8);
        findLp.bottomMargin = dp(10);
        content.addView(find, findLp);

        TextView hint = text(
                "Deze zoekbalk zoekt door alle onderliggende Xtream-categorieën tegelijk.",
                13,
                MUTED,
                false
        );
        hint.setPadding(0, 0, 0, dp(8));
        content.addView(hint);
    }

    private void searchGlobal(String query) {
        if (globalLoaded) {
            renderGlobalResults(query);
            return;
        }

        showLoading("Volledige " + ("movie".equals(mode) ? "filmbibliotheek" : "seriebibliotheek") + " laden…");

        String action = "movie".equals(mode) ? "get_vod_streams" : "get_series";

        new Thread(() -> {
            try {
                JSONArray arr = new JSONArray(get(config.api(action)));
                List<XtreamItem> all = new ArrayList<>();

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;

                    XtreamItem item = parseItem(o);
                    if (item != null) all.add(item);
                }

                runOnUiThread(() -> {
                    globalItems.clear();
                    globalItems.addAll(all);
                    globalLoaded = true;
                    renderGlobalResults(query);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> showMessage("De volledige bibliotheek kon niet worden geladen."));
            }
        }).start();
    }

    private void renderGlobalResults(String query) {
        currentView = "global";
        String normalizedQuery = normalize(query);
        List<XtreamItem> matches = new ArrayList<>();

        for (XtreamItem item : globalItems) {
            if (matchesQuery(item.name, normalizedQuery)) {
                matches.add(item);
            }
        }

        content.removeAllViews();

        Button back = button("← Terug naar categorieën");
        back.setOnClickListener(v -> loadCategories());
        content.addView(back);

        TextView title = text(
                "Zoekresultaten voor “" + query + "”",
                24,
                Color.WHITE,
                true
        );
        title.setPadding(0, dp(14), 0, dp(6));
        content.addView(title);

        TextView count = text(
                matches.size() + (matches.size() == 1 ? " resultaat" : " resultaten") + " in de volledige bibliotheek",
                14,
                BLUE,
                false
        );
        count.setPadding(0, 0, 0, dp(12));
        content.addView(count);

        addSearchAgainBox();

        TextView xtreamLabel = text("XTREAM", 16, BLUE, true);
        xtreamLabel.setPadding(0, dp(4), 0, dp(8));
        content.addView(xtreamLabel);

        renderCards(matches, 250);

        addYouTubeResults(query);
    }

    private void addYouTubeResults(String query) {
        TextView ytLabel = text("YT • YouTube", 20, Color.WHITE, true);
        ytLabel.setPadding(0, dp(18), 0, dp(8));
        content.addView(ytLabel);

        TextView ytHint = text(
                "YouTube-resultaten in The One. YT = YouTube.",
                13,
                MUTED,
                false
        );
        ytHint.setPadding(0, 0, 0, dp(8));
        content.addView(ytHint);

        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(text("YT-resultaten laden…", 15, MUTED, false));
        content.addView(holder);

        new Thread(() -> {
            try {
                List<YouTubeItem> results = searchYouTube(query);
                runOnUiThread(() -> renderYouTubeResults(holder, results));
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    holder.removeAllViews();
                    holder.addView(text("YT-resultaten konden niet worden geladen.", 15, MUTED, false));
                });
            }
        }).start();
    }

    private List<YouTubeItem> searchYouTube(String query) throws Exception {
        String apiKey = readPrivateYouTubeKey();
        if (apiKey.isEmpty() || apiKey.startsWith("PRIVATE_")) {
            throw new IllegalStateException("YouTube API key ontbreekt");
        }

        String encoded = URLEncoder.encode(query == null ? "" : query.trim(), "UTF-8");
        URL url = new URL(
                "https://www.googleapis.com/youtube/v3/search"
                        + "?part=snippet&type=video&maxResults=20"
                        + "&q=" + encoded
                        + "&key=" + URLEncoder.encode(apiKey, "UTF-8")
        );

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "TheOneMediaPlayer/1.7");

            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            if (stream == null) throw new IllegalStateException("HTTP " + code);

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line).append('\n');
            }

            JSONObject root = new JSONObject(body.toString());
            if (code < 200 || code >= 300 || root.has("error")) {
                String message = root.optJSONObject("error") == null
                        ? "HTTP " + code
                        : root.optJSONObject("error").optString("message", "YouTube API fout");
                throw new IllegalStateException(message);
            }

            JSONArray items = root.optJSONArray("items");
            List<YouTubeItem> out = new ArrayList<>();
            if (items == null) return out;

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;

                JSONObject id = item.optJSONObject("id");
                JSONObject snippet = item.optJSONObject("snippet");
                if (id == null || snippet == null) continue;

                String videoId = id.optString("videoId", "");
                if (videoId.isEmpty()) continue;

                String title = htmlUnescape(snippet.optString("title", "YouTube-video"));
                String channel = htmlUnescape(snippet.optString("channelTitle", ""));

                String thumbnail = "";
                JSONObject thumbs = snippet.optJSONObject("thumbnails");
                if (thumbs != null) {
                    JSONObject medium = thumbs.optJSONObject("medium");
                    JSONObject high = thumbs.optJSONObject("high");
                    JSONObject def = thumbs.optJSONObject("default");
                    if (medium != null) thumbnail = medium.optString("url", "");
                    if (thumbnail.isEmpty() && high != null) thumbnail = high.optString("url", "");
                    if (thumbnail.isEmpty() && def != null) thumbnail = def.optString("url", "");
                }
                if (thumbnail.isEmpty()) {
                    thumbnail = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
                }

                out.add(new YouTubeItem(videoId, title, channel, thumbnail));
            }

            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readPrivateYouTubeKey() {
        SharedPreferences prefs = getSharedPreferences("media_player", Context.MODE_PRIVATE);
        String saved = prefs.getString("youtube_api_key", "").trim();
        if (!saved.isEmpty() && !saved.startsWith("PRIVATE_")) {
            return saved;
        }

        try (InputStream in = getAssets().open("private_youtube_key.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            String bundled = line == null ? "" : line.trim();

            if (!bundled.isEmpty() && !bundled.startsWith("PRIVATE_")) {
                prefs.edit().putString("youtube_api_key", bundled).apply();
            }
            return bundled;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String htmlUnescape(String value) {
        return value == null ? "" : value
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private void renderYouTubeResults(LinearLayout holder, List<YouTubeItem> results) {
        holder.removeAllViews();

        if (results.isEmpty()) {
            holder.addView(text("Geen YT-resultaten gevonden.", 15, MUTED, false));
            return;
        }

        for (YouTubeItem item : results) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setBackground(PremiumUi.card(this));
            card.setElevation(dp(3));
            card.setPadding(dp(12), dp(12), dp(12), dp(12));

            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(Color.rgb(11, 18, 28));
            LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(dp(150), dp(90));
            imageLp.rightMargin = dp(12);
            card.addView(thumb, imageLp);
            loadImage(thumb, item.thumbnailUrl);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            card.addView(info, new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            ));

            TextView badge = text("YT", 12, Color.WHITE, true);
            badge.setBackground(PremiumUi.badge(this));
            badge.setPadding(dp(9), dp(3), dp(9), dp(3));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            badgeLp.bottomMargin = dp(6);
            info.addView(badge, badgeLp);
            TextView ytTitle = text(item.title, 17, Color.WHITE, true);
            ytTitle.setMaxLines(2);
            ytTitle.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(ytTitle);

            if (!item.channel.isEmpty()) {
                TextView channel = text(item.channel, 13, MUTED, false);
                channel.setPadding(0, dp(3), 0, dp(6));
                info.addView(channel);
            }

            Button play = button("▶ Afspelen");
            play.setOnClickListener(v -> openYouTubeVideoId(item.videoId));
            info.addView(play);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardLp.bottomMargin = dp(9);
            holder.addView(card, cardLp);
        }
    }

    private void openYouTubeVideoId(String videoId) {
        try {
            Intent intent = new Intent(this, YouTubeActivity.class);
            intent.putExtra("video_id", videoId == null ? "" : videoId.trim());
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private void addSearchAgainBox() {
        EditText search = PremiumUi.searchField(this, "Nieuwe zoekopdracht…");
        content.addView(search);

        Button find = button("Opnieuw zoeken");
        find.setOnClickListener(v -> {
            String q = search.getText().toString().trim();
            if (!q.isEmpty()) renderGlobalResults(q);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(14);
        content.addView(find, lp);
    }

    private boolean matchesQuery(String name, String normalizedQuery) {
        if (normalizedQuery.isEmpty()) return true;

        String normalizedName = normalize(name);
        String[] words = normalizedQuery.split("\\s+");

        for (String word : words) {
            if (!word.isEmpty() && !normalizedName.contains(word)) return false;
        }
        return true;
    }

    private String normalize(String value) {
        String s = value == null ? "" : value.toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = s.replaceAll("\\p{M}+", "");
        s = s.replaceAll("[^a-z0-9]+", " ").trim();
        return s;
    }

    private void loadItems(Category category) {
        List<XtreamItem> cached = categoryItemCache.get(category.id);
        List<XtreamItem> fallback = cached == null
                ? new ArrayList<>()
                : new ArrayList<>(cached);

        if (!fallback.isEmpty()) {
            renderItems(category, fallback);
        } else {
            showLoading(category.name + " laden…");
        }

        String action;
        if ("live".equals(mode)) action = "get_live_streams";
        else if ("movie".equals(mode)) action = "get_vod_streams";
        else action = "get_series";

        new Thread(() -> {
            List<XtreamItem> fresh = new ArrayList<>();

            for (int attempt = 0; attempt < 2 && fresh.isEmpty(); attempt++) {
                try {
                    JSONArray arr = new JSONArray(
                            get(config.api(action, "category_id", category.id))
                    );
                    fresh = parseItems(arr);
                } catch (Throwable ignored) {
                }

                if (fresh.isEmpty() && attempt == 0) {
                    try {
                        Thread.sleep(450);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            List<XtreamItem> result = new ArrayList<>(fresh);
            runOnUiThread(() -> {
                if (!result.isEmpty()) {
                    categoryItemCache.put(category.id, new ArrayList<>(result));
                    renderItems(category, result);
                    return;
                }

                if (!fallback.isEmpty()) {
                    renderItems(category, fallback);
                    return;
                }

                showItemsRetry(category);
            });
        }).start();
    }

    private List<XtreamItem> parseItems(JSONArray arr) {
        List<XtreamItem> items = new ArrayList<>();
        if (arr == null) return items;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;

            XtreamItem item = parseItem(o);
            if (item != null) items.add(item);
        }

        return items;
    }

    private void showItemsRetry(Category category) {
        content.removeAllViews();

        Button back = button("← Categorieën");
        back.setOnClickListener(v -> loadCategories());
        content.addView(back);

        TextView message = text(
                "Deze map gaf tijdelijk geen resultaten.",
                17,
                Color.WHITE,
                false
        );
        message.setPadding(0, dp(14), 0, dp(12));
        content.addView(message);

        Button retry = button("Opnieuw laden");
        retry.setOnClickListener(v -> loadItems(category));
        content.addView(retry);
    }

    private XtreamItem parseItem(JSONObject o) {
        if ("series".equals(mode)) {
            String id = firstNonEmpty(
                    o.optString("series_id", ""),
                    o.optString("id", ""),
                    o.optString("stream_id", "")
            );
            if (id.isEmpty()) return null;

            return new XtreamItem(
                    id,
                    firstNonEmpty(o.optString("name", ""), o.optString("title", ""), "Serie"),
                    "",
                    o.optString("rating", ""),
                    firstNonEmpty(
                            o.optString("cover", ""),
                            o.optString("cover_big", ""),
                            o.optString("stream_icon", ""),
                            o.optString("poster", ""),
                            o.optString("movie_image", ""),
                            o.optString("backdrop", ""),
                            firstImageValue(o.opt("backdrop_path"))
                    ),
                    extractYear(o)
            );
        }

        String id = o.optString("stream_id", "");
        if (id.isEmpty()) return null;

        String ext = o.optString("container_extension", "");
        if ("live".equals(mode) && ext.isEmpty()) ext = "ts";

        return new XtreamItem(
                id,
                firstNonEmpty(o.optString("name", ""), o.optString("title", ""), "Media"),
                ext,
                o.optString("rating", ""),
                o.optString("stream_icon", ""),
                "live".equals(mode) ? "" : extractYear(o)
        );
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private String firstImageValue(Object value) {
        if (value == null || value == JSONObject.NULL) return "";

        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                String candidate = firstImageValue(arr.opt(i));
                if (!candidate.isEmpty()) return candidate;
            }
            return "";
        }

        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            String[] keys = new String[]{"url", "file_path", "path", "image", "cover"};
            for (String key : keys) {
                String candidate = firstImageValue(obj.opt(key));
                if (!candidate.isEmpty()) return candidate;
            }
            return "";
        }

        String text = String.valueOf(value).trim();
        if (text.startsWith("[") || text.startsWith("{")) {
            try {
                return firstImageValue(new org.json.JSONTokener(text).nextValue());
            } catch (Throwable ignored) {
            }
        }
        return text;
    }

    private String extractYear(JSONObject item) {
        String[] candidates = new String[]{
                item.optString("year", ""),
                item.optString("releaseDate", ""),
                item.optString("release_date", ""),
                item.optString("added", "")
        };

        for (String candidate : candidates) {
            String year = cleanYear(candidate);
            if (!year.isEmpty()) return year;
        }
        return "";
    }

    private String cleanYear(String value) {
        String v = value == null ? "" : value.trim();
        if (v.matches("(19|20)\\d{2}")) return v;

        if (v.length() >= 4) {
            String firstFour = v.substring(0, 4);
            if (firstFour.matches("(19|20)\\d{2}")) return firstFour;
        }

        if (v.matches("\\d{10,13}")) {
            try {
                long timestamp = Long.parseLong(v);
                if (v.length() <= 10) timestamp *= 1000L;
                return new SimpleDateFormat("yyyy", Locale.US).format(new Date(timestamp));
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private String cleanRating(String raw) {
        String v = raw == null ? "" : raw.trim().replace(",", ".");
        if (v.isEmpty()) return "";
        try {
            double rating = Double.parseDouble(v);
            if (rating <= 0.0) return "";
            return String.format(Locale.US, "%.1f", rating);
        } catch (Throwable ignored) {
            return v.length() > 5 ? "" : v;
        }
    }

    private String providerFor(String rawTitle) {
        String value = rawTitle == null ? "" : rawTitle.trim().toUpperCase(Locale.ROOT);
        if (startsWithAny(value, "D+ -", "D+ |", "D+:", "[D+]", "DISNEY+ -", "DISNEY PLUS -")) return "Disney+";
        if (startsWithAny(value, "NF -", "NF |", "[NF]", "NETFLIX -", "NETFLIX |")) return "Netflix";
        if (startsWithAny(value, "AP -", "APV -", "PRIME -", "AMAZON -", "AMAZON PRIME -")) return "Prime Video";
        if (startsWithAny(value, "HBO -", "MAX -", "HBO MAX -")) return "Max";
        if (startsWithAny(value, "ATV+ -", "APPLE TV+ -", "APPLE TV -")) return "Apple TV+";
        if (startsWithAny(value, "P+ -", "PARAMOUNT+ -", "PARAMOUNT -")) return "Paramount+";
        return "Xtream";
    }

    private String displayTitle(String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        String upper = title.toUpperCase(Locale.ROOT);
        String[] prefixes = new String[]{
                "D+ -", "D+ |", "D+:", "[D+]", "DISNEY+ -", "DISNEY PLUS -",
                "NF -", "NF |", "[NF]", "NETFLIX -", "NETFLIX |",
                "AP -", "APV -", "PRIME -", "AMAZON -", "AMAZON PRIME -",
                "HBO -", "MAX -", "HBO MAX -",
                "ATV+ -", "APPLE TV+ -", "APPLE TV -",
                "P+ -", "PARAMOUNT+ -", "PARAMOUNT -"
        };
        for (String prefix : prefixes) {
            if (upper.startsWith(prefix)) return title.substring(prefix.length()).trim();
        }
        return title;
    }

    private boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }

    private void renderItems(Category category, List<XtreamItem> items) {
        currentView = "items";
        currentCategory = category;
        currentCategoryItems.clear();
        currentCategoryItems.addAll(items);
        categoryItems.clear();
        categoryItems.addAll(items);
        content.removeAllViews();

        Button back = button("← Categorieën");
        back.setOnClickListener(v -> loadCategories());
        content.addView(back);

        TextView title = text(category.name, 25, Color.WHITE, true);
        title.setPadding(0, dp(14), 0, dp(10));
        content.addView(title);

        if ("live".equals(mode)) {
            addLiveCategorySearch(category);
        } else {
            TextView hint = text(
                    "Gebruik de zoekbalk op de categoriepagina om door alle " +
                            ("movie".equals(mode) ? "films" : "series") + " tegelijk te zoeken.",
                    13,
                    MUTED,
                    false
            );
            hint.setPadding(0, 0, 0, dp(10));
            content.addView(hint);
        }

        renderCards(items, Integer.MAX_VALUE);
    }

    private void addLiveCategorySearch(Category category) {
        EditText search = PremiumUi.searchField(this, "Zoeken in " + category.name);
        content.addView(search);

        Button searchButton = button("Zoeken");
        searchButton.setOnClickListener(v -> renderLiveFiltered(category, search.getText().toString()));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(12);
        content.addView(searchButton, lp);
    }

    private void renderLiveFiltered(Category category, String query) {
        String q = normalize(query);
        List<XtreamItem> filtered = new ArrayList<>();

        for (XtreamItem item : categoryItems) {
            if (matchesQuery(item.name, q)) filtered.add(item);
        }

        content.removeAllViews();

        Button back = button("← Categorieën");
        back.setOnClickListener(v -> loadCategories());
        content.addView(back);

        TextView title = text(category.name, 25, Color.WHITE, true);
        title.setPadding(0, dp(14), 0, dp(10));
        content.addView(title);

        addLiveCategorySearch(category);
        renderCards(filtered, 150);
    }

    private void renderCards(List<XtreamItem> items, int max) {
        if (items.isEmpty()) {
            content.addView(text("Geen resultaten.", 16, MUTED, false));
            return;
        }

        int limit = Math.min(max, items.size());

        for (int i = 0; i < limit; i++) {
            XtreamItem item = items.get(i);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation("live".equals(mode) ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            card.setBackground(PremiumUi.card(this));
            card.setElevation(dp(4));
            card.setPadding(dp(12), dp(12), dp(12), dp(12));

            if (!"live".equals(mode)) {
                ImageView poster = new ImageView(this);
                poster.setAdjustViewBounds(true);
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                poster.setBackgroundColor(Color.rgb(11, 18, 28));

                LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(dp(88), dp(132));
                posterLp.rightMargin = dp(12);
                card.addView(poster, posterLp);

                if (item.imageUrl != null && item.imageUrl.startsWith("http")) {
                    loadImage(poster, item.imageUrl);
                }
            }

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            if ("live".equals(mode)) {
                card.addView(info, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            } else {
                card.addView(info, new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                ));
            }

            TextView title = text(
                    "live".equals(mode) ? item.name : displayTitle(item.name),
                    "live".equals(mode) ? 19 : 18,
                    Color.WHITE,
                    true
            );
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLineSpacing(0f, 0.96f);
            info.addView(title);

            if (!"live".equals(mode)) {
                StringBuilder meta = new StringBuilder(providerFor(item.name));
                if (!item.year.isEmpty()) meta.append(" • ").append(item.year);

                String rating = cleanRating(item.rating);
                if (!rating.isEmpty()) meta.append(" • ⭐ ").append(rating);

                TextView metaView = text(meta.toString(), 13, BLUE, false);
                metaView.setSingleLine(true);
                metaView.setEllipsize(TextUtils.TruncateAt.END);
                metaView.setPadding(0, dp(4), 0, dp(7));
                info.addView(metaView);
            } else {
                String rating = cleanRating(item.rating);
                if (!rating.isEmpty()) {
                    TextView metaView = text("⭐ " + rating, 13, BLUE, false);
                    metaView.setPadding(0, dp(4), 0, dp(7));
                    info.addView(metaView);
                }
            }

            Button open = button("series".equals(mode) ? "Afleveringen" : "▶  Afspelen");
            open.setOnClickListener(v -> {
                if ("series".equals(mode)) loadSeriesEpisodes(item);
                else play(item);
            });
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            openLp.topMargin = dp(3);
            info.addView(open, openLp);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = dp(9);
            content.addView(card, lp);
        }

        if (items.size() > limit) {
            TextView more = text(
                    "Eerste " + limit + " resultaten getoond. Gebruik de zoekbalk om sneller te vinden.",
                    14,
                    MUTED,
                    false
            );
            more.setPadding(0, dp(6), 0, dp(10));
            content.addView(more);
        }
    }

    private void loadImage(ImageView view, String urlValue) {
        imagePool.submit(() -> {
            Bitmap bitmap = null;
            for (String candidate : imageCandidates(urlValue)) {
                bitmap = downloadBitmap(candidate);
                if (bitmap != null) break;
            }

            if (bitmap != null) {
                Bitmap ready = bitmap;
                runOnUiThread(() -> {
                    if (!isFinishing()) view.setImageBitmap(ready);
                });
            }
        });
    }

    private List<String> imageCandidates(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;

        String value = raw.trim().replace(" ", "%20");
        if (value.isEmpty()) return out;

        if (value.startsWith("//")) value = "https:" + value;
        if (value.startsWith("/")) value = config.server + value;

        if (value.startsWith("http://") || value.startsWith("https://")) {
            out.add(value);
        }

        if (value.startsWith("http://")) {
            String secure = "https://" + value.substring("http://".length());
            if (!out.contains(secure)) out.add(secure);
        } else if (value.startsWith("https://")) {
            String plain = "http://" + value.substring("https://".length());
            if (!out.contains(plain)) out.add(plain);
        }

        // Sommige HULU/EN-items verwijzen naar een oude proxytx-host die
        // niet meer via DNS bereikbaar is. De bestandsnaam is wel dezelfde
        // TMDB-poster-id, dus gebruik die als veilige afbeeldingsfallback.
        try {
            String path = new URL(value).getPath();
            int slash = path.lastIndexOf('/');
            String fileName = slash >= 0 ? path.substring(slash + 1) : "";
            if (!fileName.isEmpty()
                    && (value.contains("/images/series/")
                    || value.toLowerCase(Locale.ROOT).contains("proxytx.cloud"))) {
                String tmdb = "https://image.tmdb.org/t/p/w342/" + fileName;
                if (!out.contains(tmdb)) out.add(tmdb);
            }
        } catch (Throwable ignored) {
        }

        return out;
    }

    private Bitmap downloadBitmap(String urlValue) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlValue).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
            if (config != null && config.server != null && !config.server.isEmpty()) {
                conn.setRequestProperty("Referer", config.server + "/");
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;

            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void play(XtreamItem item) {
        ArrayList<String> queue = new ArrayList<>();
        queue.add(config.stream(mode, item.id, item.extension));
        startPlaybackWhenLineFree(queue, mode);
    }

    private void startPlaybackWhenLineFree(ArrayList<String> queue, String kind) {
        if (queue == null || queue.isEmpty()) return;

        // active_cons on Xtream panels can stay at 1/1 for several seconds after
        // the previous socket is already closed. Treating that value as a hard
        // gate made the app get stuck on "Streamlijn nog bezet".
        //
        // Start the requested title immediately. MainActivity retries the real
        // media connection briefly if the provider has not released the old
        // session yet. That makes switching deterministic without trusting a
        // stale account-status counter.
        showLoading("Stream starten…");
        launchPlayback(queue, kind);
    }

    private int parseConnectionCount(Object value) {
        if (value == null || value == JSONObject.NULL) return -1;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void launchPlayback(ArrayList<String> queue, String kind) {
        if (isFinishing() || queue == null || queue.isEmpty()) return;

        Intent intent = new Intent(this, MainActivity.class);
        if (queue.size() == 1) {
            intent.putExtra("play_url", queue.get(0));
        } else {
            intent.putStringArrayListExtra("play_queue", queue);
        }
        intent.putExtra("play_kind", kind);
        startActivity(intent);
    }

    private void showConnectionBusy(
            int active,
            int max,
            ArrayList<String> queue,
            String kind
    ) {
        content.removeAllViews();

        String count = active >= 0 && max > 0
                ? " (" + active + "/" + max + ")"
                : "";

        TextView message = text(
                "De streamlijn is nog bezet" + count
                        + ". The One kan pas starten zodra de bestaande stream is vrijgegeven.",
                17,
                Color.WHITE,
                false
        );
        message.setPadding(0, dp(12), 0, dp(12));
        content.addView(message);

        Button retry = button("Automatisch opnieuw controleren");
        retry.setOnClickListener(v -> startPlaybackWhenLineFree(queue, kind));
        content.addView(retry);

        Button back = PremiumUi.chipButton(this, "← Terug");
        back.setOnClickListener(v -> loadCategories());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        backLp.topMargin = dp(8);
        content.addView(back, backLp);
    }

    private void loadSeriesEpisodes(XtreamItem series) {
        currentView = "episodes";
        List<XtreamItem> cached = seriesEpisodeCache.get(series.id);
        List<XtreamItem> fallback = cached == null
                ? new ArrayList<>()
                : new ArrayList<>(cached);

        if (!fallback.isEmpty()) {
            renderEpisodes(series, fallback);
        } else {
            showLoading(series.name + " laden…");
        }

        new Thread(() -> {
            List<XtreamItem> episodes = new ArrayList<>();

            for (int attempt = 0; attempt < 2 && episodes.isEmpty(); attempt++) {
                try {
                    String raw = get(config.api("get_series_info", "series_id", series.id));
                    Object data = new org.json.JSONTokener(raw).nextValue();
                    collectEpisodes(data, "", episodes);
                } catch (Throwable ignored) {
                }

                if (episodes.isEmpty() && attempt == 0) {
                    try {
                        Thread.sleep(450);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            episodes.sort((a, b) -> compareEpisodeNames(a.name, b.name));
            List<XtreamItem> result = new ArrayList<>(episodes);

            runOnUiThread(() -> {
                if (!result.isEmpty()) {
                    seriesEpisodeCache.put(series.id, new ArrayList<>(result));
                    renderEpisodes(series, result);
                    return;
                }

                if (!fallback.isEmpty()) {
                    renderEpisodes(series, fallback);
                    return;
                }

                renderEpisodes(series, result);
            });
        }).start();
    }

    private void collectEpisodes(Object node, String seasonHint, List<XtreamItem> out) {
        if (node == null || node == JSONObject.NULL) return;

        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectEpisodes(arr.opt(i), seasonHint, out);
            }
            return;
        }

        if (!(node instanceof JSONObject)) return;

        JSONObject ep = (JSONObject) node;
        String id = firstNonEmpty(
                ep.optString("id", ""),
                ep.optString("stream_id", ""),
                ep.optString("episode_id", "")
        );

        boolean looksLikeEpisode = !id.isEmpty()
                && (ep.has("episode_num")
                || ep.has("episode")
                || ep.has("episode_number")
                || ep.has("container_extension")
                || ep.has("title"));

        if (looksLikeEpisode) {
            String season = firstNonEmpty(
                    ep.optString("season", ""),
                    ep.optString("season_number", ""),
                    ep.optString("season_num", ""),
                    seasonHint,
                    "0"
            );
            String episodeNumber = firstNonEmpty(
                    ep.optString("episode_num", ""),
                    ep.optString("episode", ""),
                    ep.optString("episode_number", ""),
                    ep.optString("num", "")
            );
            String title = firstNonEmpty(
                    ep.optString("title", ""),
                    ep.optString("name", ""),
                    episodeNumber.isEmpty() ? "Aflevering" : "Aflevering " + episodeNumber
            );
            String extension = firstNonEmpty(
                    ep.optString("container_extension", ""),
                    ep.optString("extension", ""),
                    "mp4"
            );

            for (XtreamItem existing : out) {
                if (existing.id.equals(id)) return;
            }

            String label = "S" + season
                    + (episodeNumber.isEmpty() ? "" : " E" + episodeNumber)
                    + " • " + title;

            JSONObject info = ep.optJSONObject("info");
            String episodeImage = firstNonEmpty(
                    ep.optString("movie_image", ""),
                    ep.optString("cover", ""),
                    ep.optString("cover_big", ""),
                    ep.optString("stream_icon", ""),
                    ep.optString("poster", ""),
                    info == null ? "" : info.optString("movie_image", ""),
                    info == null ? "" : info.optString("cover", ""),
                    info == null ? "" : info.optString("cover_big", ""),
                    info == null ? "" : info.optString("stream_icon", "")
            );

            out.add(new XtreamItem(
                    id,
                    label,
                    extension,
                    "",
                    episodeImage,
                    ""
            ));
            return;
        }

        java.util.Iterator<String> keys = ep.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String nextSeason = seasonFromKey(key, seasonHint);
            collectEpisodes(ep.opt(key), nextSeason, out);
        }
    }

    private String seasonFromKey(String key, String fallback) {
        if (key == null) return fallback == null ? "" : fallback;

        String trimmed = key.trim();
        if (trimmed.matches("\\d+")) return trimmed;

        String digits = trimmed.replaceAll("[^0-9]", "");
        if (trimmed.toLowerCase(Locale.ROOT).contains("season") && !digits.isEmpty()) {
            return digits;
        }

        return fallback == null ? "" : fallback;
    }

    private void renderEpisodes(XtreamItem series, List<XtreamItem> episodes) {
        currentView = "episodes";
        content.removeAllViews();

        Button back = button("← 1 stap terug");
        back.setOnClickListener(v -> goBackOneStep());
        content.addView(back);

        TextView title = text(series.name, 25, Color.WHITE, true);
        title.setPadding(0, dp(14), 0, dp(12));
        content.addView(title);

        if (episodes.isEmpty()) {
            content.addView(text("Geen afleveringen gevonden.", 16, MUTED, false));
            return;
        }

        for (int index = 0; index < episodes.size(); index++) {
            XtreamItem ep = episodes.get(index);
            final int startIndex = index;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(PremiumUi.card(this));
            card.setElevation(dp(3));
            card.setPadding(dp(12), dp(12), dp(12), dp(12));

            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setAdjustViewBounds(false);
            thumbnail.setBackgroundColor(Color.rgb(11, 18, 28));

            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(
                    dp(132),
                    dp(78)
            );
            thumbLp.rightMargin = dp(12);
            card.addView(thumbnail, thumbLp);

            String thumbnailUrl = firstNonEmpty(ep.imageUrl, series.imageUrl);
            if (thumbnailUrl.startsWith("http")) {
                loadImage(thumbnail, thumbnailUrl);
            }

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            card.addView(info, new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            ));

            TextView episodeTitle = text(ep.name, 18, Color.WHITE, true);
            episodeTitle.setMaxLines(3);
            episodeTitle.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(episodeTitle);

            Button play = button("▶ Afspelen");
            play.setOnClickListener(v -> {
                ArrayList<String> queue = new ArrayList<>();

                for (int i = startIndex; i < episodes.size(); i++) {
                    XtreamItem queuedEpisode = episodes.get(i);
                    queue.add(config.stream(
                            "series",
                            queuedEpisode.id,
                            queuedEpisode.extension
                    ));
                }

                startPlaybackWhenLineFree(queue, "series");
            });

            LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            playLp.topMargin = dp(8);
            info.addView(play, playLp);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = dp(9);
            content.addView(card, lp);
        }
    }

    private void goBackOneStep() {
        if ("episodes".equals(currentView)) {
            if (currentCategory != null && !currentCategoryItems.isEmpty()) {
                renderItems(currentCategory, new ArrayList<>(currentCategoryItems));
            } else {
                loadCategories();
            }
            return;
        }
        if ("items".equals(currentView) || "global".equals(currentView)) {
            loadCategories();
            return;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        goBackOneStep();
    }

    private int compareEpisodeNames(String left, String right) {
        int[] a = episodeKey(left);
        int[] b = episodeKey(right);

        if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
        if (a[1] != b[1]) return Integer.compare(a[1], b[1]);

        String l = left == null ? "" : left;
        String r = right == null ? "" : right;
        return l.compareToIgnoreCase(r);
    }

    private int[] episodeKey(String label) {
        int season = 0;
        int episode = 0;
        String value = label == null ? "" : label;

        try {
            int s = value.indexOf('S');
            int e = value.indexOf(" E");
            if (s >= 0 && e > s) {
                season = Integer.parseInt(value.substring(s + 1, e).trim());
            }

            if (e >= 0) {
                int startEpisode = e + 2;
                int endEpisode = value.indexOf(' ', startEpisode);
                if (endEpisode < 0) endEpisode = value.length();
                episode = Integer.parseInt(value.substring(startEpisode, endEpisode).trim());
            }
        } catch (Throwable ignored) {
        }

        return new int[]{season, episode};
    }

    private void openYouTubeSearch(String query) {
        try {
            Intent intent = new Intent(this, YouTubeActivity.class);
            intent.putExtra("query", query == null ? "" : query.trim());
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private void showLoading(String message) {
        content.removeAllViews();
        content.addView(text(message, 18, BLUE, true));
    }

    private void showMessage(String message) {
        content.removeAllViews();
        TextView t = text(message, 17, Color.WHITE, false);
        t.setPadding(0, dp(12), 0, dp(12));
        content.addView(t);
    }

    private String get(String urlValue) throws Exception {
        HttpURLConnection conn = null;

        try {
            conn = (HttpURLConnection) new URL(urlValue).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "TheOneMediaPlayer/0.9");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cache-Control", "no-cache, no-store");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setRequestProperty("Connection", "close");

            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            if (in == null) throw new IllegalStateException("HTTP " + code);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {

                StringBuilder out = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }

                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code);
                }

                return out.toString();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private Button button(String label) {
        return PremiumUi.primaryButton(this, label);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);

        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        imagePool.shutdownNow();
        super.onDestroy();
    }

    private static class Category {
        final String id;
        final String name;

        Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static class YouTubeItem {
        final String videoId;
        final String title;
        final String channel;
        final String thumbnailUrl;

        YouTubeItem(String videoId, String title, String channel, String thumbnailUrl) {
            this.videoId = videoId;
            this.title = title == null ? "" : title;
            this.channel = channel == null ? "" : channel;
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        }
    }

    private static class XtreamItem {
        final String id;
        final String name;
        final String extension;
        final String rating;
        final String imageUrl;
        final String year;

        XtreamItem(
                String id,
                String name,
                String extension,
                String rating,
                String imageUrl,
                String year
        ) {
            this.id = id;
            this.name = name;
            this.extension = extension;
            this.rating = rating == null ? "" : rating;
            this.imageUrl = imageUrl == null ? "" : imageUrl;
            this.year = year == null ? "" : year;
        }
    }
}
