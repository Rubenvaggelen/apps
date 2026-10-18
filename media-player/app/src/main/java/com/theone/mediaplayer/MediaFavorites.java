package com.theone.mediaplayer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Device-local, credential-free favorites for Live TV, movies and series.
 * The same code is compiled into the phone and Google TV packages.
 */
final class MediaFavorites {
    private static final String PREFS = "media_player";
    private static final String KEY = "catalog_favorites_v1";
    private static final int MAX = 300;

    static final class Item {
        final String kind, id, name, extension, year;
        Item(String kind, String id, String name, String extension, String year) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.extension = extension;
            this.year = year;
        }
        String key() { return kind + ":" + id; }
        String label() {
            return "live".equals(kind) ? "TV-kanaal" :
                   "movie".equals(kind) ? "Film" : "Serie";
        }
    }

    private MediaFavorites() {}

    static boolean valid(Item item) {
        return item != null
            && ("live".equals(item.kind) || "movie".equals(item.kind) || "series".equals(item.kind))
            && item.id != null && item.id.matches("(?:source2:)?[0-9]{1,12}")
            && item.name != null && !item.name.trim().isEmpty();
    }

    static List<Item> decode(String json) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < array.length() && out.size() < MAX; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                Item item = new Item(
                    o.optString("kind"), o.optString("id"), o.optString("name"),
                    o.optString("extension"), o.optString("year")
                );
                if (!valid(item)) continue;
                boolean present = false;
                for (Item previous : out) if (previous.key().equals(item.key())) { present=true; break; }
                if (!present) out.add(item);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static String encode(List<Item> items) {
        JSONArray array = new JSONArray();
        for (Item item : items) {
            if (!valid(item) || array.length() >= MAX) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("kind", item.kind);
                o.put("id", item.id);
                o.put("name", item.name.length() > 180 ? item.name.substring(0, 180) : item.name);
                o.put("extension", item.extension == null ? "" : item.extension.replaceAll("[^a-zA-Z0-9]", ""));
                o.put("year", item.year == null ? "" : item.year);
                array.put(o);
            } catch (Exception ignored) {}
        }
        return array.toString();
    }

    static synchronized List<Item> list(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return decode(p.getString(KEY, "[]"));
    }

    static synchronized boolean contains(Context context, String kind, String id) {
        for (Item item : list(context)) if (item.kind.equals(kind) && item.id.equals(id)) return true;
        return false;
    }

    /** Returns true when added, false when removed. */
    static synchronized boolean toggle(Context context, Item candidate) {
        if (!valid(candidate)) return false;
        List<Item> items = list(context);
        boolean removed = items.removeIf(item -> item.key().equals(candidate.key()));
        if (!removed) items.add(0, candidate);
        if (items.size() > MAX) items = new ArrayList<>(items.subList(0, MAX));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, encode(items)).apply();
        return !removed;
    }
}
