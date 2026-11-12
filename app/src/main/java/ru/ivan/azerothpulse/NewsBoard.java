package ru.ivan.azerothpulse;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** News checks and read state are independent from manual realm refreshes. */
final class NewsBoard {
    private static final long CHECK_INTERVAL = 15 * 60 * 1000L;
    private static final String CACHE = "blizzardNewsV1";
    private static final String CHECKED = "blizzardNewsChecked";
    private static final String SEEN = "blizzardNewsSeenV1";
    private static final String TRANSLATION = "blizzardNewsTranslation";
    private final SharedPreferences preferences;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> seen;
    private final Set<String> bookmarks;
    private JSONArray savedItems;
    private final NewsReader reader;
    private final Runnable poll = this::poll;
    private JSONArray items;
    private Translator translator;
    private long lastAttempt = -1;
    private boolean loading;
    private boolean translating;
    private boolean active;
    private boolean closed;
    private String status = "saved";
    private String translationStatus = "idle";

    NewsBoard(SharedPreferences preferences, JSONArray seed, Runnable changed) {
        this.preferences = preferences;
        this.changed = changed;
        reader = new NewsReader(preferences, changed);
        seen = new HashSet<>(preferences.getStringSet(SEEN, Collections.emptySet()));
        bookmarks = new HashSet<>(preferences.getStringSet("newsBookmarksV1", Collections.emptySet()));
        try { savedItems = new JSONArray(preferences.getString("savedNewsV1", "[]")); }
        catch (JSONException invalid) { savedItems = new JSONArray(); }
        JSONArray cached;
        try { cached = new JSONArray(preferences.getString(CACHE, "[]")); }
        catch (JSONException invalid) { cached = new JSONArray(); }
        items = BlizzardNews.merge(cached, seed);
    }

    static String display(JSONObject item, String field) {
        String localized = item.optString(field + "Ru");
        return localized.isEmpty() ? item.optString(field) : localized;
    }

    JSONObject state() throws JSONException {
        int unread = 0;
        int untranslated = 0;
        JSONArray cards = new JSONArray();
        JSONArray visible = visibleItems();
        for (int i = 0; i < visible.length(); i++) {
            JSONObject item = visible.getJSONObject(i);
            if (!seen.contains(item.getString("version")) && contains(items, item.optString("id"))) unread++;
            if (item.optString("titleRu").isEmpty()) untranslated++;
            cards.put(new JSONObject().put("id", item.getString("id"))
                .put("publishedAt", item.getString("publishedAt"))
                .put("hotfixes", item.optString("url").contains("hotfix"))
                .put("title", display(item, "title")).put("summary", display(item, "summary"))
                .put("unread", !seen.contains(item.getString("version"))).put("bookmarked", bookmarks.contains(item.getString("id")))
                .put("translation", item.optString("translation")));
        }
        return new JSONObject().put("items", cards).put("unread", unread).put("untranslated", untranslated)
            .put("status", status).put("translationStatus", translationStatus).put("reader", reader.state())
            .put("checkedAt", preferences.getString(CHECKED, ""));
    }

    void resume() {
        active = true;
        poll();
    }

    private void poll() {
        main.removeCallbacks(poll);
        if (!active || closed) return;
        long since = SystemClock.elapsedRealtime() - lastAttempt;
        if (lastAttempt < 0 || since >= CHECK_INTERVAL) {
            refresh();
            since = 0;
        }
        main.postDelayed(poll, Math.max(1_000L, CHECK_INTERVAL - since));
    }

    void pause() {
        active = false;
        main.removeCallbacks(poll);
    }

    void refresh() {
        if (closed || loading) return;
        lastAttempt = SystemClock.elapsedRealtime();
        loading = true;
        status = "loading";
        changed.run();
        worker.execute(() -> {
            JSONArray english = fetch(BlizzardNews.EN);
            JSONArray russian = fetch(BlizzardNews.RU);
            main.post(() -> receive(english, russian));
        });
    }

    private static JSONArray fetch(String address) {
        try { return BlizzardNews.parse(ForeverSources.download(address), address); }
        catch (Exception failure) { return null; }
    }

    private void receive(JSONArray english, JSONArray russian) {
        if (closed) return;
        loading = false;
        if (english == null && (russian == null || russian.length() == 0)) {
            status = "offline";
        } else {
            items = BlizzardNews.merge(items, english == null ? new JSONArray() : english,
                russian == null ? new JSONArray() : russian);
            status = english == null || russian == null ? "partial" : "checked";
            Set<String> current = new HashSet<>();
            for (int i = 0; i < items.length(); i++) current.add(items.optJSONObject(i).optString("version"));
            for (int i = 0; i < savedItems.length(); i++) current.add(savedItems.optJSONObject(i).optString("version"));
            seen.retainAll(current);
            preferences.edit().putString(CACHE, items.toString()).putStringSet(SEEN, new HashSet<>(seen))
                .putString(CHECKED, Instant.now().toString()).apply();
        }
        changed.run();
        translateIfEnabled();
    }

    private static boolean contains(JSONArray array, String id) {
        for (int i = 0; i < array.length(); i++) if (id.equals(array.optJSONObject(i).optString("id"))) return true;
        return false;
    }

    private JSONArray visibleItems() {
        JSONArray all = new JSONArray();
        for (int i = 0; i < items.length(); i++) all.put(items.optJSONObject(i));
        for (int i = 0; i < savedItems.length(); i++) {
            JSONObject item = savedItems.optJSONObject(i);
            if (item != null && bookmarks.contains(item.optString("id")) && !contains(all, item.optString("id"))) all.put(item);
        }
        return all;
    }

    void bookmark(String id) {
        if (closed) return;
        if (bookmarks.contains(id)) bookmarks.remove(id);
        else {
            if (bookmarks.size() >= 50) return;
            JSONArray all = visibleItems();
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.optJSONObject(i);
                if (id.equals(item.optString("id"))) { bookmarks.add(id); break; }
            }
        }
        JSONArray next = new JSONArray();
        JSONArray all = visibleItems();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (bookmarks.contains(item.optString("id"))) next.put(item);
        }
        savedItems = next;
        preferences.edit().putStringSet("newsBookmarksV1", new HashSet<>(bookmarks)).putString("savedNewsV1", next.toString()).apply();
        changed.run();
    }

    void viewed(String id) {
        if (closed) return;
        boolean updated = false;
        JSONArray all = visibleItems();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (id.equals(item.optString("id"))) updated |= seen.add(item.optString("version"));
        }
        if (updated) {
            preferences.edit().putStringSet(SEEN, new HashSet<>(seen)).apply();
            changed.run();
        }
    }

    void read(String id) {
        if (closed) return;
        JSONArray all = visibleItems();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (!item.optString("id").equals(id)) continue;
            viewed(id);
            try {
                String address = item.getString("url");
                if (id.equals(BlizzardNews.articleId(URI.create(address)))) {
                    reader.open(id, item.getString("version"), address, preferences.getBoolean(TRANSLATION, false));
                }
            } catch (Exception invalid) {
                // Ignore an invalid cached address.
            }
            return;
        }
    }

    void closeArticle() { if (!closed) reader.collapse(); }

    void showOriginal(boolean show) { if (!closed) reader.showOriginal(show); }

    void enableArticleTranslation() {
        if (closed) return;
        enableTranslation();
        reader.translate();
    }

    void enableTranslation() {
        if (closed) return;
        preferences.edit().putBoolean(TRANSLATION, true).apply();
        translateIfEnabled();
    }

    private void translateIfEnabled() {
        if (closed || translating || !preferences.getBoolean(TRANSLATION, false)) return;
        ArrayList<JSONObject> pending = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item.optString("titleRu").isEmpty() && !item.optString("title").isEmpty()) pending.add(item);
        }
        if (pending.isEmpty()) return;
        if (translator == null) translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.RUSSIAN).build());
        translating = true;
        translationStatus = "loading";
        changed.run();
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(unused -> translateNext(pending, 0))
            .addOnFailureListener(error -> translationFailed());
    }

    private void translateNext(ArrayList<JSONObject> pending, int index) {
        if (closed) return;
        if (index == pending.size()) {
            translating = false;
            translationStatus = "idle";
            changed.run();
            translateIfEnabled();
            return;
        }
        JSONObject input = pending.get(index);
        Task<String> title = translator.translate(input.optString("title"));
        String summary = input.optString("summary");
        Task<String> description = summary.isEmpty() ? Tasks.forResult("") : translator.translate(summary);
        Tasks.<String>whenAllSuccess(title, description).addOnSuccessListener(values -> {
            if (closed) return;
            if (values.get(0).trim().isEmpty()) { translationFailed(); return; }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (!item.optString("version").equals(input.optString("version")) || !item.optString("titleRu").isEmpty()) continue;
                try {
                    item.put("titleRu", values.get(0)).put("summaryRu", values.get(1)).put("translation", "machine");
                    preferences.edit().putString(CACHE, items.toString()).apply();
                    changed.run();
                } catch (JSONException invalid) {
                    translationFailed();
                    return;
                }
            }
            translateNext(pending, index + 1);
        }).addOnFailureListener(error -> translationFailed());
    }

    private void translationFailed() {
        if (closed) return;
        translating = false;
        translationStatus = "failed";
        changed.run();
    }

    void close() {
        closed = true;
        pause();
        worker.shutdownNow();
        reader.close();
        if (translator != null) translator.close();
    }
}
