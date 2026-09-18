package com.theone.mediaplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class TmdbCatalogActivity extends Activity {
    private static final int BLUE = Color.rgb(32, 184, 255);
    private static final int BG = Color.rgb(5, 7, 11);
    private static final int PANEL = Color.rgb(17, 23, 34);
    private static final int MUTED = Color.rgb(154, 166, 178);
    private static final String TMDB_BASE = "https://api.themoviedb.org/3";
    private static final String TMDB_IMAGE = "https://image.tmdb.org/t/p/w342";

    private SharedPreferences prefs;
    private LinearLayout root;
    private LinearLayout content;
    private String mode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("media_player", Context.MODE_PRIVATE);
        mode = getIntent().getStringExtra("mode");
        if (mode == null || mode.trim().isEmpty()) mode = "all";
        renderHome();
    }

    private void renderHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button back = button("←");
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView brand = text("  THE ONE  •  MEDIA PLAYER", 22, BLUE, true);
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        String heading = "all".equals(mode) ? "Ontdekken" : ("movie".equals(mode) ? "Films" : "Series");
        TextView title = text(heading, 32, Color.WHITE, true);
        title.setPadding(0, dp(18), 0, dp(6));
        root.addView(title);

        TextView sub = text(
                "HDO-achtige catalogus via TMDB. Afspelen gebeurt uitsluitend via je eigen ingestelde mediabron.",
                15,
                MUTED,
                false
        );
        sub.setPadding(0, 0, 0, dp(16));
        root.addView(sub);

        if (tmdbToken().isEmpty()) {
            LinearLayout warning = card();
            warning.addView(text("TMDB-token ontbreekt", 20, Color.WHITE, true));
            TextView detail = text(
                    "Ga naar Instellingen > Bron instellen en plak daar je TMDB API Read Access Token. De token blijft lokaal op dit apparaat.",
                    15,
                    MUTED,
                    false
            );
            detail.setPadding(0, dp(8), 0, dp(12));
            warning.addView(detail);
            Button settings = button("Token instellen");
            settings.setOnClickListener(v -> startActivity(new Intent(this, SourceConfigActivity.class)));
            warning.addView(settings);
            root.addView(warning);
            setContentView(scroll);
            return;
        }

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);

        EditText search = new EditText(this);
        search.setHint("Zoek film of serie");
        search.setHintTextColor(MUTED);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
        search.setBackgroundColor(PANEL);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button find = button("Zoeken");
        LinearLayout.LayoutParams findLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        findLp.leftMargin = dp(10);
        searchRow.addView(find, findLp);
        root.addView(searchRow);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(0, dp(12), 0, dp(12));

        if (!"tv".equals(mode)) {
            Button movies = button("Trending films");
            movies.setOnClickListener(v -> loadMedia("/trending/movie/week?language=nl-NL", "Trending films", "movie"));
            filters.addView(movies);
        }

        if (!"movie".equals(mode)) {
            Button series = button("Trending series");
            series.setOnClickListener(v -> loadMedia("/trending/tv/week?language=nl-NL", "Trending series", "tv"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            filters.addView(series, lp);
        }
        root.addView(filters);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);

        find.setOnClickListener(v -> {
            String q = search.getText().toString().trim();
            if (q.isEmpty()) return;
            String endpoint;
            String forcedType;
            if ("movie".equals(mode)) {
                endpoint = "/search/movie?language=nl-NL&include_adult=false&query=" + enc(q);
                forcedType = "movie";
            } else if ("tv".equals(mode)) {
                endpoint = "/search/tv?language=nl-NL&include_adult=false&query=" + enc(q);
                forcedType = "tv";
            } else {
                endpoint = "/search/multi?language=nl-NL&include_adult=false&query=" + enc(q);
                forcedType = null;
            }
            loadMedia(endpoint, "Zoekresultaten voor “" + q + "”", forcedType);
        });

        setContentView(scroll);

        if ("movie".equals(mode)) {
            loadMedia("/trending/movie/week?language=nl-NL", "Trending films", "movie");
        } else if ("tv".equals(mode)) {
            loadMedia("/trending/tv/week?language=nl-NL", "Trending series", "tv");
        } else {
            loadMedia("/trending/all/week?language=nl-NL", "Trending", null);
        }
    }

    private void loadMedia(String endpoint, String heading, String forcedType) {
        showLoading(heading);
        new Thread(() -> {
            try {
                JSONObject data = new JSONObject(getJson(TMDB_BASE + endpoint, true));
                JSONArray results = data.optJSONArray("results");
                List<MediaMeta> items = new ArrayList<>();
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.optJSONObject(i);
                        if (item == null) continue;
                        MediaMeta meta = MediaMeta.from(item, forcedType);
                        if (meta != null) items.add(meta);
                    }
                }
                runOnUiThread(() -> renderResults(heading, items));
            } catch (Exception e) {
                runOnUiThread(() -> showError("Catalogus kon niet worden geladen", e));
            }
        }).start();
    }

    private void renderResults(String heading, List<MediaMeta> items) {
        content.removeAllViews();
        content.addView(sectionTitle(heading));
        if (items.isEmpty()) {
            content.addView(text("Geen resultaten gevonden.", 16, MUTED, false));
            return;
        }
        for (MediaMeta meta : items) addMediaCard(content, meta);
        addTmdbAttribution(content);
    }

    private void addMediaCard(LinearLayout parent, MediaMeta meta) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);

        ImageView poster = new ImageView(this);
        poster.setAdjustViewBounds(true);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(Color.rgb(25, 31, 42));
        LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(dp(108), dp(162));
        posterLp.rightMargin = dp(14);
        card.addView(poster, posterLp);
        if (meta.posterPath != null && !meta.posterPath.trim().isEmpty()) {
            loadImage(poster, TMDB_IMAGE + meta.posterPath);
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        info.addView(text(meta.title, 20, Color.WHITE, true));
        String typeLabel = "movie".equals(meta.type) ? "Film" : "Serie";
        TextView metaLine = text(typeLabel + (meta.year.isEmpty() ? "" : " • " + meta.year) + (meta.rating > 0 ? " • ★ " + String.format(Locale.US, "%.1f", meta.rating) : ""), 14, BLUE, false);
        metaLine.setPadding(0, dp(4), 0, dp(7));
        info.addView(metaLine);

        String overview = meta.overview == null || meta.overview.trim().isEmpty() ? "Geen beschrijving beschikbaar." : meta.overview;
        if (overview.length() > 280) overview = overview.substring(0, 277) + "…";
        TextView desc = text(overview, 14, MUTED, false);
        desc.setPadding(0, 0, 0, dp(10));
        info.addView(desc);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button details = button("Details");
        details.setOnClickListener(v -> loadDetails(meta));
        actions.addView(details);

        Button play = button("Open in mijn speler");
        play.setOnClickListener(v -> openFromOwnSource(meta, null, null));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playLp.leftMargin = dp(8);
        actions.addView(play, playLp);
        info.addView(actions);

        parent.addView(card);
    }

    private void loadDetails(MediaMeta meta) {
        showLoading(meta.title);
        new Thread(() -> {
            try {
                String endpoint = "/" + ("movie".equals(meta.type) ? "movie" : "tv") + "/" + meta.id + "?language=nl-NL";
                JSONObject details = new JSONObject(getJson(TMDB_BASE + endpoint, true));
                runOnUiThread(() -> renderDetails(meta, details));
            } catch (Exception e) {
                runOnUiThread(() -> showError("Details konden niet worden geladen", e));
            }
        }).start();
    }

    private void renderDetails(MediaMeta meta, JSONObject details) {
        content.removeAllViews();
        Button back = button("← Terug naar resultaten");
        back.setOnClickListener(v -> {
            if ("movie".equals(mode)) loadMedia("/trending/movie/week?language=nl-NL", "Trending films", "movie");
            else if ("tv".equals(mode)) loadMedia("/trending/tv/week?language=nl-NL", "Trending series", "tv");
            else loadMedia("/trending/all/week?language=nl-NL", "Trending", null);
        });
        content.addView(back);

        TextView title = sectionTitle(meta.title);
        title.setPadding(0, dp(12), 0, dp(8));
        content.addView(title);

        StringBuilder detailLine = new StringBuilder();
        if ("movie".equals(meta.type)) {
            int runtime = details.optInt("runtime", 0);
            if (runtime > 0) detailLine.append(runtime).append(" min");
        } else {
            int seasons = details.optInt("number_of_seasons", 0);
            if (seasons > 0) detailLine.append(seasons).append(seasons == 1 ? " seizoen" : " seizoenen");
        }
        String genres = joinGenres(details.optJSONArray("genres"));
        if (!genres.isEmpty()) {
            if (detailLine.length() > 0) detailLine.append(" • ");
            detailLine.append(genres);
        }
        if (detailLine.length() > 0) {
            TextView line = text(detailLine.toString(), 15, BLUE, false);
            line.setPadding(0, 0, 0, dp(10));
            content.addView(line);
        }

        String overview = details.optString("overview", meta.overview);
        TextView desc = text(overview == null || overview.trim().isEmpty() ? "Geen beschrijving beschikbaar." : overview, 16, Color.WHITE, false);
        desc.setPadding(0, 0, 0, dp(14));
        content.addView(desc);

        Button open = button("▶ Open via mijn eigen bron");
        open.setOnClickListener(v -> openFromOwnSource(meta, null, null));
        content.addView(open);

        if ("tv".equals(meta.type)) {
            JSONArray seasons = details.optJSONArray("seasons");
            if (seasons != null) {
                TextView seasonTitle = sectionTitle("Seizoenen");
                seasonTitle.setPadding(0, dp(18), 0, dp(8));
                content.addView(seasonTitle);
                for (int i = 0; i < seasons.length(); i++) {
                    JSONObject s = seasons.optJSONObject(i);
                    if (s == null) continue;
                    int number = s.optInt("season_number", -1);
                    if (number < 0) continue;
                    String name = s.optString("name", "Seizoen " + number);
                    int count = s.optInt("episode_count", 0);
                    Button season = button(name + (count > 0 ? " • " + count + " afl." : ""));
                    season.setOnClickListener(v -> loadSeason(meta, number, name));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = dp(8);
                    content.addView(season, lp);
                }
            }
        }
        addTmdbAttribution(content);
    }

    private void loadSeason(MediaMeta series, int seasonNumber, String seasonName) {
        showLoading(series.title + " • " + seasonName);
        new Thread(() -> {
            try {
                String endpoint = "/tv/" + series.id + "/season/" + seasonNumber + "?language=nl-NL";
                JSONObject season = new JSONObject(getJson(TMDB_BASE + endpoint, true));
                runOnUiThread(() -> renderSeason(series, seasonNumber, seasonName, season));
            } catch (Exception e) {
                runOnUiThread(() -> showError("Seizoen kon niet worden geladen", e));
            }
        }).start();
    }

    private void renderSeason(MediaMeta series, int seasonNumber, String seasonName, JSONObject season) {
        content.removeAllViews();

        Button back = button("← Terug naar " + series.title);
        back.setOnClickListener(v -> loadDetails(series));
        content.addView(back);

        TextView title = sectionTitle(series.title + " • " + seasonName);
        title.setPadding(0, dp(12), 0, dp(10));
        content.addView(title);

        JSONArray episodes = season.optJSONArray("episodes");
        if (episodes == null || episodes.length() == 0) {
            content.addView(text("Geen afleveringen gevonden.", 16, MUTED, false));
            return;
        }

        for (int i = 0; i < episodes.length(); i++) {
            JSONObject ep = episodes.optJSONObject(i);
            if (ep == null) continue;
            int epNumber = ep.optInt("episode_number", i + 1);
            String name = ep.optString("name", "Aflevering " + epNumber);
            String overview = ep.optString("overview", "");
            String airDate = ep.optString("air_date", "");

            LinearLayout card = card();
            card.addView(text(String.format(Locale.US, "S%02dE%02d • %s", seasonNumber, epNumber, name), 18, Color.WHITE, true));
            String small = airDate.trim().isEmpty() ? "" : airDate;
            if (!small.isEmpty()) {
                TextView air = text(small, 13, BLUE, false);
                air.setPadding(0, dp(3), 0, dp(5));
                card.addView(air);
            }
            if (!overview.trim().isEmpty()) {
                String shortOverview = overview.length() > 240 ? overview.substring(0, 237) + "…" : overview;
                TextView desc = text(shortOverview, 14, MUTED, false);
                desc.setPadding(0, 0, 0, dp(8));
                card.addView(desc);
            }

            Button play = button("▶ Open aflevering in mijn speler");
            int finalEpNumber = epNumber;
            play.setOnClickListener(v -> openFromOwnSource(series, seasonNumber, finalEpNumber));
            card.addView(play);
            content.addView(card);
        }
        addTmdbAttribution(content);
    }

    private void openFromOwnSource(MediaMeta meta, Integer season, Integer episode) {
        String sourceType = prefs.getString("source_type", "M3U");
        if ("M3U".equals(sourceType)) {
            findInM3u(meta, season, episode);
        } else if ("XTREAM".equals(sourceType)) {
            findInXtream(meta, season, episode);
        } else {
            Toast.makeText(
                    this,
                    "De HDO-achtige catalogus werkt al. Automatisch koppelen aan Stalker/MAC wordt apart toegevoegd; kies nu M3U of Xtream om direct vanuit deze catalogus af te spelen.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void findInM3u(MediaMeta meta, Integer season, Integer episode) {
        String playlist = prefs.getString("m3u_url", "").trim();
        if (playlist.isEmpty()) {
            Toast.makeText(this, "Stel eerst je eigen M3U-bron in via Instellingen.", Toast.LENGTH_LONG).show();
            return;
        }
        showLoading("Zoeken in jouw bron…");
        new Thread(() -> {
            try {
                String m3u = getText(playlist);
                List<SourceEntry> all = parseM3u(m3u);
                String query = sourceQuery(meta, season, episode);
                List<SourceEntry> matches = rankMatches(all, query, meta.title, 60);
                runOnUiThread(() -> renderSourceMatches(meta, season, episode, matches));
            } catch (Exception e) {
                runOnUiThread(() -> showError("M3U-bron kon niet worden gelezen", e));
            }
        }).start();
    }

    private void findInXtream(MediaMeta meta, Integer season, Integer episode) {
        String server = trimSlash(prefs.getString("xtream_server", ""));
        String user = prefs.getString("xtream_user", "");
        String pass = prefs.getString("xtream_pass", "");
        if (server.trim().isEmpty() || user.trim().isEmpty() || pass.trim().isEmpty()) {
            Toast.makeText(this, "Stel eerst je eigen Xtream-server, gebruikersnaam en wachtwoord in.", Toast.LENGTH_LONG).show();
            return;
        }

        showLoading("Zoeken in jouw Xtream-bron…");
        new Thread(() -> {
            try {
                if ("movie".equals(meta.type)) {
                    String url = xtreamApi(server, user, pass, "get_vod_streams", null);
                    JSONArray streams = new JSONArray(getJson(url, false));
                    JSONObject best = bestJsonMatch(streams, meta.title, "name");
                    if (best == null) throw new IllegalStateException("Geen passende film gevonden in jouw Xtream-bibliotheek.");
                    String streamId = String.valueOf(best.opt("stream_id"));
                    String ext = best.optString("container_extension", "mp4");
                    String playUrl = server + "/movie/" + Uri.encode(user) + "/" + Uri.encode(pass) + "/" + streamId + "." + ext;
                    runOnUiThread(() -> playUrl(playUrl));
                    return;
                }

                String seriesUrl = xtreamApi(server, user, pass, "get_series", null);
                JSONArray series = new JSONArray(getJson(seriesUrl, false));
                JSONObject bestSeries = bestJsonMatch(series, meta.title, "name");
                if (bestSeries == null) throw new IllegalStateException("Geen passende serie gevonden in jouw Xtream-bibliotheek.");
                String seriesId = String.valueOf(bestSeries.opt("series_id"));

                if (season == null || episode == null) {
                    runOnUiThread(() -> Toast.makeText(
                            this,
                            "Serie gevonden. Kies eerst een seizoen en aflevering in de catalogus.",
                            Toast.LENGTH_LONG
                    ).show());
                    return;
                }

                String infoUrl = xtreamApi(server, user, pass, "get_series_info", seriesId);
                JSONObject info = new JSONObject(getJson(infoUrl, false));
                JSONObject episodesObj = info.optJSONObject("episodes");
                if (episodesObj == null) throw new IllegalStateException("Geen afleveringen ontvangen van je Xtream-bron.");
                JSONArray seasonEpisodes = episodesObj.optJSONArray(String.valueOf(season));
                if (seasonEpisodes == null) throw new IllegalStateException("Dit seizoen staat niet in je Xtream-bron.");

                JSONObject found = null;
                for (int i = 0; i < seasonEpisodes.length(); i++) {
                    JSONObject ep = seasonEpisodes.optJSONObject(i);
                    if (ep == null) continue;
                    int epNum = ep.optInt("episode_num", ep.optInt("episode_number", -1));
                    if (epNum == episode) {
                        found = ep;
                        break;
                    }
                }
                if (found == null) throw new IllegalStateException("Deze aflevering staat niet in je Xtream-bron.");

                String id = String.valueOf(found.opt("id"));
                String ext = found.optString("container_extension", "mp4");
                String playUrl = server + "/series/" + Uri.encode(user) + "/" + Uri.encode(pass) + "/" + id + "." + ext;
                runOnUiThread(() -> playUrl(playUrl));
            } catch (Exception e) {
                runOnUiThread(() -> showError("Geen afspeelbare match gevonden", e));
            }
        }).start();
    }

    private void renderSourceMatches(MediaMeta meta, Integer season, Integer episode, List<SourceEntry> matches) {
        content.removeAllViews();

        Button back = button("← Terug");
        back.setOnClickListener(v -> {
            if (season != null && episode != null) {
                loadSeason(meta, season, "Seizoen " + season);
            } else {
                loadDetails(meta);
            }
        });
        content.addView(back);

        String query = sourceQuery(meta, season, episode);
        TextView title = sectionTitle("Matches voor " + query);
        title.setPadding(0, dp(12), 0, dp(10));
        content.addView(title);

        if (matches.isEmpty()) {
            content.addView(text(
                    "Geen passende stream in jouw M3U-bron gevonden. De catalogus is wel beschikbaar; alleen titels die jouw eigen provider aanbiedt kunnen in The One worden afgespeeld.",
                    16,
                    MUTED,
                    false
            ));
            return;
        }

        for (SourceEntry entry : matches) {
            LinearLayout card = card();
            card.addView(text(entry.title, 18, Color.WHITE, true));
            TextView group = text(entry.group, 13, BLUE, false);
            group.setPadding(0, dp(4), 0, dp(8));
            card.addView(group);
            Button play = button("▶ Afspelen");
            play.setOnClickListener(v -> playUrl(entry.url));
            card.addView(play);
            content.addView(card);
        }
    }

    private void playUrl(String url) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("play_url", url);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private String sourceQuery(MediaMeta meta, Integer season, Integer episode) {
        if (season != null && episode != null) {
            return String.format(Locale.US, "%s S%02dE%02d", meta.title, season, episode);
        }
        return meta.title + (meta.year.isEmpty() ? "" : " " + meta.year);
    }

    private List<SourceEntry> parseM3u(String m3u) {
        List<SourceEntry> result = new ArrayList<>();
        String pendingTitle = null;
        String pendingGroup = "Overig";
        for (String rawLine : m3u.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                pendingTitle = comma >= 0 ? line.substring(comma + 1).trim() : "Media";
                pendingGroup = extractGroup(line);
            } else if (!line.isEmpty() && !line.startsWith("#") && pendingTitle != null) {
                result.add(new SourceEntry(pendingTitle, pendingGroup, line));
                pendingTitle = null;
                pendingGroup = "Overig";
            }
        }
        return result;
    }

    private List<SourceEntry> rankMatches(List<SourceEntry> entries, String primaryQuery, String fallbackQuery, int limit) {
        List<ScoredEntry> scored = new ArrayList<>();
        for (SourceEntry entry : entries) {
            int score = matchScore(entry.title, primaryQuery);
            if (score < 50) score = Math.max(score, matchScore(entry.title, fallbackQuery) - 10);
            if (score >= 50) scored.add(new ScoredEntry(entry, score));
        }
        scored.sort(Comparator.comparingInt((ScoredEntry s) -> s.score).reversed());
        List<SourceEntry> result = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < limit; i++) result.add(scored.get(i).entry);
        return result;
    }

    private JSONObject bestJsonMatch(JSONArray items, String query, String field) {
        JSONObject best = null;
        int bestScore = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            int score = matchScore(item.optString(field, ""), query);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return bestScore >= 55 ? best : null;
    }

    private int matchScore(String candidate, String query) {
        String c = normalize(candidate);
        String q = normalize(query);
        if (c.isEmpty() || q.isEmpty()) return 0;
        if (c.equals(q)) return 100;
        if (c.startsWith(q) || q.startsWith(c)) return 90;
        if (c.contains(q) || q.contains(c)) return 82;

        String[] words = q.split(" ");
        int matched = 0;
        for (String word : words) {
            if (word.length() > 1 && c.contains(word)) matched++;
        }
        if (words.length == 0) return 0;
        return (int) Math.round((matched * 70.0) / words.length);
    }

    private String normalize(String value) {
        if (value == null) return "";
        String v = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return v.replace('&', ' ')
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractGroup(String extinf) {
        String token = "group-title=\"";
        int start = extinf.indexOf(token);
        if (start < 0) return "Overig";
        start += token.length();
        int end = extinf.indexOf('"', start);
        return end > start ? extinf.substring(start, end) : "Overig";
    }

    private String xtreamApi(String server, String user, String pass, String action, String seriesId) {
        StringBuilder url = new StringBuilder(server)
                .append("/player_api.php?username=").append(enc(user))
                .append("&password=").append(enc(pass))
                .append("&action=").append(enc(action));
        if (seriesId != null) url.append("&series_id=").append(enc(seriesId));
        return url.toString();
    }

    private String tmdbToken() {
        return prefs.getString("tmdb_token", "").trim();
    }

    private String getJson(String url, boolean tmdbAuth) throws Exception {
        return requestText(url, tmdbAuth);
    }

    private String getText(String url) throws Exception {
        return requestText(url, false);
    }

    private String requestText(String urlValue, boolean tmdbAuth) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlValue).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "TheOneMediaPlayer/0.4");
            if (tmdbAuth) conn.setRequestProperty("Authorization", "Bearer " + tmdbToken());

            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) throw new IllegalStateException("HTTP " + code);

            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + out.toString().trim());
            }
            return out.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void loadImage(ImageView target, String url) {
        target.setTag(url);
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(18000);
                conn.setDoInput(true);
                conn.connect();
                Bitmap bitmap = BitmapFactory.decodeStream(conn.getInputStream());
                runOnUiThread(() -> {
                    Object tag = target.getTag();
                    if (bitmap != null && url.equals(tag)) target.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private String joinGenres(JSONArray genres) {
        if (genres == null) return "";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < genres.length(); i++) {
            JSONObject g = genres.optJSONObject(i);
            if (g != null) {
                String name = g.optString("name", "");
                if (!name.trim().isEmpty()) names.add(name);
            }
        }
        StringBuilder joined = new StringBuilder();
        for (String name : names) {
            if (joined.length() > 0) joined.append(", ");
            joined.append(name);
        }
        return joined.toString();
    }

    private void addTmdbAttribution(LinearLayout parent) {
        TextView credit = text(
                "Catalogusdata en afbeeldingen: TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                12,
                MUTED,
                false
        );
        credit.setPadding(0, dp(18), 0, dp(6));
        parent.addView(credit);
    }

    private void showLoading(String label) {
        content.removeAllViews();
        content.addView(sectionTitle(label));
        ProgressBar progress = new ProgressBar(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        content.addView(progress, lp);
    }

    private void showError(String heading, Exception e) {
        content.removeAllViews();
        content.addView(sectionTitle(heading));
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
        content.addView(text(message, 15, MUTED, false));
        Button settings = button("Instellingen");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SourceConfigActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        content.addView(settings, lp);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(PANEL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView t = text(value, 24, Color.WHITE, true);
        t.setPadding(0, dp(8), 0, dp(12));
        return t;
    }

    private Button button(String label) {
        Button b = PremiumUi.primaryButton(this, label);
        b.setFocusable(true);
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

    private String enc(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    private String trimSlash(String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static class MediaMeta {
        final int id;
        final String type;
        final String title;
        final String overview;
        final String posterPath;
        final String year;
        final double rating;

        MediaMeta(int id, String type, String title, String overview, String posterPath, String year, double rating) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.overview = overview;
            this.posterPath = posterPath;
            this.year = year;
            this.rating = rating;
        }

        static MediaMeta from(JSONObject item, String forcedType) {
            String type = forcedType != null ? forcedType : item.optString("media_type", "");
            if (!"movie".equals(type) && !"tv".equals(type)) return null;
            int id = item.optInt("id", 0);
            if (id <= 0) return null;

            String title = "movie".equals(type) ? item.optString("title", "") : item.optString("name", "");
            if (title.trim().isEmpty()) return null;

            String date = "movie".equals(type) ? item.optString("release_date", "") : item.optString("first_air_date", "");
            String year = date.length() >= 4 ? date.substring(0, 4) : "";
            return new MediaMeta(
                    id,
                    type,
                    title,
                    item.optString("overview", ""),
                    item.optString("poster_path", ""),
                    year,
                    item.optDouble("vote_average", 0)
            );
        }
    }

    private static class SourceEntry {
        final String title;
        final String group;
        final String url;

        SourceEntry(String title, String group, String url) {
            this.title = title;
            this.group = group;
            this.url = url;
        }
    }

    private static class ScoredEntry {
        final SourceEntry entry;
        final int score;

        ScoredEntry(SourceEntry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }
}
