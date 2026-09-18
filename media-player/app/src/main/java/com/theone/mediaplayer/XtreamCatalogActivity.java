package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
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
import java.util.ArrayList;
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
    private final ExecutorService imagePool = Executors.newFixedThreadPool(4);

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
        scroll.setBackgroundColor(BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button back = button("←");
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = text("  " + heading(), 27, Color.WHITE, true);
        header.addView(title);
        root.addView(header);

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
        showLoading("Categorieën laden…");

        String action = "live".equals(mode)
                ? "get_live_categories"
                : ("movie".equals(mode) ? "get_vod_categories" : "get_series_categories");

        new Thread(() -> {
            try {
                JSONArray arr = new JSONArray(get(config.api(action)));
                List<Category> categories = new ArrayList<>();

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;

                    String id = o.optString("category_id", "");
                    String name = o.optString("category_name", "Categorie");
                    if (!id.isEmpty()) categories.add(new Category(id, name));
                }

                runOnUiThread(() -> renderCategories(categories));
            } catch (Throwable e) {
                runOnUiThread(() -> showMessage("Categorieën konden niet worden geladen."));
            }
        }).start();
    }

    private void renderCategories(List<Category> categories) {
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
            Button b = button(category.name);
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

        EditText search = new EditText(this);
        search.setHint("Typ een titel…");
        search.setHintTextColor(MUTED);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setBackgroundColor(PANEL);
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
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

        if (matches.isEmpty() && "movie".equals(mode)) {
            TextView notFound = text(
                    "Niet gevonden in je Xtream-bibliotheek. Je kunt dezelfde titel op YouTube zoeken.",
                    15,
                    MUTED,
                    false
            );
            notFound.setPadding(0, dp(8), 0, dp(8));
            content.addView(notFound);

            Button youtube = button("Zoek op YouTube");
            youtube.setOnClickListener(v -> openYouTubeSearch(query));
            content.addView(youtube);

            // De gebruiker heeft expliciet gevraagd om bij ontbrekende films door te zoeken op YouTube.
            openYouTubeSearch(query);
            return;
        }

        renderCards(matches, 250);
    }

    private void addSearchAgainBox() {
        EditText search = new EditText(this);
        search.setHint("Nieuwe zoekopdracht…");
        search.setHintTextColor(MUTED);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setBackgroundColor(PANEL);
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
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
        showLoading(category.name + " laden…");

        String action;
        if ("live".equals(mode)) action = "get_live_streams";
        else if ("movie".equals(mode)) action = "get_vod_streams";
        else action = "get_series";

        new Thread(() -> {
            try {
                JSONArray arr = new JSONArray(get(config.api(action, "category_id", category.id)));
                List<XtreamItem> items = new ArrayList<>();

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;

                    XtreamItem item = parseItem(o);
                    if (item != null) items.add(item);
                }

                runOnUiThread(() -> renderItems(category, items));
            } catch (Throwable e) {
                runOnUiThread(() -> showMessage("Deze categorie kon niet worden geladen."));
            }
        }).start();
    }

    private XtreamItem parseItem(JSONObject o) {
        if ("series".equals(mode)) {
            String id = o.optString("series_id", "");
            if (id.isEmpty()) return null;

            return new XtreamItem(
                    id,
                    firstNonEmpty(o.optString("name", ""), o.optString("title", ""), "Serie"),
                    "",
                    o.optString("rating", ""),
                    o.optString("cover", ""),
                    firstNonEmpty(o.optString("releaseDate", ""), o.optString("year", ""), "")
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
                firstNonEmpty(o.optString("year", ""), o.optString("added", ""), "")
        );
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private void renderItems(Category category, List<XtreamItem> items) {
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

        renderCards(items, 150);
    }

    private void addLiveCategorySearch(Category category) {
        EditText search = new EditText(this);
        search.setHint("Zoeken in " + category.name);
        search.setHintTextColor(MUTED);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setBackgroundColor(PANEL);
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
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
            card.setBackgroundColor(PANEL);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));

            if (!"live".equals(mode)) {
                ImageView poster = new ImageView(this);
                poster.setAdjustViewBounds(true);
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                poster.setBackgroundColor(Color.rgb(25, 31, 42));

                LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(dp(96), dp(144));
                posterLp.rightMargin = dp(14);
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

            info.addView(text(item.name, 19, Color.WHITE, true));

            String meta = "";
            if (!item.year.isEmpty()) meta = item.year;
            if (!item.rating.isEmpty()) {
                meta += (meta.isEmpty() ? "" : " • ") + "★ " + item.rating;
            }

            if (!meta.isEmpty()) {
                TextView metaView = text(meta, 14, BLUE, false);
                metaView.setPadding(0, dp(4), 0, dp(8));
                info.addView(metaView);
            }

            Button open = button("series".equals(mode) ? "Afleveringen" : "▶ Afspelen");
            open.setOnClickListener(v -> {
                if ("series".equals(mode)) loadSeriesEpisodes(item);
                else play(item);
            });
            info.addView(open);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = dp(10);
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
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlValue).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "TheOneMediaPlayer/0.9");

                try (InputStream in = conn.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(in);
                    if (bitmap != null) {
                        runOnUiThread(() -> view.setImageBitmap(bitmap));
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void play(XtreamItem item) {
        String url = config.stream(mode, item.id, item.extension);
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("play_url", url);
        startActivity(intent);
    }

    private void loadSeriesEpisodes(XtreamItem series) {
        showLoading(series.name + " laden…");

        new Thread(() -> {
            try {
                JSONObject data = new JSONObject(
                        get(config.api("get_series_info", "series_id", series.id))
                );

                JSONObject episodesObj = data.optJSONObject("episodes");
                List<XtreamItem> episodes = new ArrayList<>();

                if (episodesObj != null) {
                    JSONArray seasonNames = episodesObj.names();

                    if (seasonNames != null) {
                        for (int s = 0; s < seasonNames.length(); s++) {
                            String season = seasonNames.optString(s, "");
                            JSONArray eps = episodesObj.optJSONArray(season);
                            if (eps == null) continue;

                            for (int i = 0; i < eps.length(); i++) {
                                JSONObject ep = eps.optJSONObject(i);
                                if (ep == null) continue;

                                String id = ep.optString("id", ep.optString("stream_id", ""));
                                if (id.isEmpty()) continue;

                                String title = firstNonEmpty(
                                        ep.optString("title", ""),
                                        ep.optString("name", ""),
                                        "Aflevering"
                                );
                                String epNum = ep.optString("episode_num", "");
                                String label = "S" + season
                                        + (epNum.isEmpty() ? "" : " E" + epNum)
                                        + " • " + title;

                                String ext = ep.optString("container_extension", "mp4");

                                episodes.add(new XtreamItem(
                                        id,
                                        label,
                                        ext,
                                        "",
                                        "",
                                        ""
                                ));
                            }
                        }
                    }
                }

                runOnUiThread(() -> renderEpisodes(series, episodes));
            } catch (Throwable e) {
                runOnUiThread(() -> showMessage("Afleveringen konden niet worden geladen."));
            }
        }).start();
    }

    private void renderEpisodes(XtreamItem series, List<XtreamItem> episodes) {
        content.removeAllViews();

        Button back = button("← Terug");
        back.setOnClickListener(v -> loadCategories());
        content.addView(back);

        TextView title = text(series.name, 25, Color.WHITE, true);
        title.setPadding(0, dp(14), 0, dp(12));
        content.addView(title);

        if (episodes.isEmpty()) {
            content.addView(text("Geen afleveringen gevonden.", 16, MUTED, false));
            return;
        }

        for (XtreamItem ep : episodes) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(PANEL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));

            card.addView(text(ep.name, 18, Color.WHITE, true));

            Button play = button("▶ Afspelen");
            play.setOnClickListener(v -> {
                String url = config.stream("series", ep.id, ep.extension);
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("play_url", url);
                startActivity(intent);
            });
            card.addView(play);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = dp(9);
            content.addView(card, lp);
        }
    }

    private void openYouTubeSearch(String query) {
        try {
            String encoded = URLEncoder.encode(query == null ? "" : query.trim(), "UTF-8");
            Uri uri = Uri.parse("https://www.youtube.com/results?search_query=" + encoded);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            try {
                intent.setPackage("com.google.android.youtube");
                startActivity(intent);
            } catch (Throwable noApp) {
                intent.setPackage(null);
                startActivity(intent);
            }
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
            conn.setRequestProperty("User-Agent", "TheOneMediaPlayer/0.9");

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
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(20, 92, 130))
        );
        return b;
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
