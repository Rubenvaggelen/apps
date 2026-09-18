package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class XtreamCatalogActivity extends Activity {
    private static final int BLUE = Color.rgb(32, 184, 255);
    private static final int BG = Color.rgb(5, 7, 11);
    private static final int PANEL = Color.rgb(17, 23, 34);
    private static final int MUTED = Color.rgb(154, 166, 178);

    private XtreamConfig config;
    private String mode;
    private LinearLayout root;
    private LinearLayout content;
    private final List<XtreamItem> currentItems = new ArrayList<>();

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

        TextView sub = text("Rechtstreeks uit jouw privé Xtream-bron.", 15, MUTED, false);
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
                List<Category> cats = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("category_id", "");
                    String name = o.optString("category_name", "Categorie");
                    if (!id.isEmpty()) cats.add(new Category(id, name));
                }
                runOnUiThread(() -> renderCategories(cats));
            } catch (Throwable e) {
                runOnUiThread(() -> showMessage("Categorieën konden niet worden geladen."));
            }
        }).start();
    }

    private void renderCategories(List<Category> categories) {
        content.removeAllViews();

        TextView label = text("Categorieën", 21, BLUE, true);
        label.setPadding(0, 0, 0, dp(10));
        content.addView(label);

        if (categories.isEmpty()) {
            showMessage("Geen categorieën gevonden.");
            return;
        }

        for (Category c : categories) {
            Button b = button(c.name);
            b.setOnClickListener(v -> loadItems(c));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = dp(8);
            content.addView(b, lp);
        }
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
                    if (items.size() >= 500) break;
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
            return new XtreamItem(id, o.optString("name", "Serie"), "", o.optString("rating", ""));
        }

        String id = o.optString("stream_id", "");
        if (id.isEmpty()) return null;
        String ext = o.optString("container_extension", "");
        if ("live".equals(mode) && ext.isEmpty()) ext = "ts";
        return new XtreamItem(id, o.optString("name", "Media"), ext, o.optString("rating", ""));
    }

    private void renderItems(Category category, List<XtreamItem> items) {
        currentItems.clear();
        currentItems.addAll(items);
        content.removeAllViews();

        Button back = button("← Categorieën");
        back.setOnClickListener(v -> loadCategories());
        content.addView(back);

        TextView title = text(category.name, 25, Color.WHITE, true);
        title.setPadding(0, dp(14), 0, dp(10));
        content.addView(title);

        EditText search = new EditText(this);
        search.setHint("Zoeken in " + category.name);
        search.setHintTextColor(MUTED);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setBackgroundColor(PANEL);
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
        content.addView(search);

        Button searchButton = button("Zoeken");
        searchButton.setOnClickListener(v -> renderFilteredItems(category, search.getText().toString()));
        LinearLayout.LayoutParams sb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sb.topMargin = dp(8);
        sb.bottomMargin = dp(12);
        content.addView(searchButton, sb);

        renderCards(items, 150);
    }

    private void renderFilteredItems(Category category, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<XtreamItem> filtered = new ArrayList<>();
        for (XtreamItem item : currentItems) {
            if (q.isEmpty() || item.name.toLowerCase(Locale.ROOT).contains(q)) filtered.add(item);
        }
        renderItems(category, filtered);
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
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(PANEL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));

            card.addView(text(item.name, 19, Color.WHITE, true));
            if (!item.rating.isEmpty()) {
                TextView rating = text("★ " + item.rating, 14, BLUE, false);
                rating.setPadding(0, dp(4), 0, dp(8));
                card.addView(rating);
            }

            Button open = button("series".equals(mode) ? "Afleveringen" : "▶ Afspelen");
            open.setOnClickListener(v -> {
                if ("series".equals(mode)) loadSeriesEpisodes(item);
                else play(item);
            });
            card.addView(open);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = dp(10);
            content.addView(card, lp);
        }

        if (items.size() > limit) {
            TextView more = text("Eerste " + limit + " resultaten getoond. Gebruik zoeken om sneller te vinden.", 14, MUTED, false);
            more.setPadding(0, dp(6), 0, dp(10));
            content.addView(more);
        }
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
                JSONObject data = new JSONObject(get(config.api("get_series_info", "series_id", series.id)));
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
                                String title = ep.optString("title", ep.optString("name", "Aflevering"));
                                String epNum = ep.optString("episode_num", "");
                                String label = "S" + season + (epNum.isEmpty() ? "" : " E" + epNum) + " • " + title;
                                String ext = ep.optString("container_extension", "mp4");
                                episodes.add(new XtreamItem(id, label, ext, ""));
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
            conn.setRequestProperty("User-Agent", "TheOneMediaPlayer/0.8");
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
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
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(20, 92, 130)));
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        XtreamItem(String id, String name, String extension, String rating) {
            this.id = id;
            this.name = name;
            this.extension = extension;
            this.rating = rating;
        }
    }
}
