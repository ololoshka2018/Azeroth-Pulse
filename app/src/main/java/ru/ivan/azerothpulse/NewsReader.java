package ru.ivan.azerothpulse;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Loads and translates a single expanded article, independently of the feed. */
final class NewsReader {
    private static final String CACHE = "blizzardReaderV1";
    private static final int CACHE_SIZE = 4;
    private static final int TRANSLATION_CHUNK = 1_200;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SharedPreferences preferences;
    private final Runnable changed;
    private final LinkedHashMap<String, JSONObject> cache = new LinkedHashMap<>(8, .75f, true);
    private Future<?> job;
    private Translator translator;
    private int request;
    private String id;
    private String version;
    private String address;
    private String status;
    private JSONObject body;
    private JSONObject original;
    private boolean showOriginal;
    private String originalStatus;

    NewsReader(SharedPreferences preferences, Runnable changed) {
        this.preferences = preferences;
        this.changed = changed;
        try {
            JSONArray saved = new JSONArray(preferences.getString(CACHE, "[]"));
            for (int i = Math.max(0, saved.length() - CACHE_SIZE); i < saved.length(); i++) {
                JSONObject entry = saved.getJSONObject(i);
                entry.getJSONObject("body");
                cache.put(entry.getString("version"), entry);
            }
        } catch (JSONException invalid) {
            cache.clear();
        }
    }

    JSONObject state() throws JSONException {
        return id == null ? null : new JSONObject().put("id", id).put("status", status).put("body", body)
            .put("showOriginal", showOriginal).put("originalStatus", originalStatus)
            .put("original", showOriginal ? original : null);
    }

    void open(String articleId, String revision, String address, boolean translate) {
        cancel();
        id = articleId;
        version = revision;
        this.address = address;
        showOriginal = false;
        originalStatus = null;
        JSONObject saved = cache.get(revision);
        body = saved == null ? null : saved.optJSONObject("body");
        original = saved == null ? null : saved.optJSONObject("original");
        if (body != null) {
            status = "ready";
            if (translate && needsTranslation()) translate();
            else changed.run();
            return;
        }
        status = "loading";
        changed.run();
        int ticket = request;
        job = worker.submit(() -> {
            try {
                JSONObject article = BlizzardArticle.fetch(address, articleId);
                main.post(() -> {
                    if (request != ticket) return;
                    body = article;
                    save();
                    status = "ready";
                    if (translate && needsTranslation()) translate();
                    else changed.run();
                });
            } catch (Exception unavailable) {
                main.post(() -> failed(ticket, "failed"));
            }
        });
    }

    void showOriginal(boolean show) {
        if (body == null || needsTranslation()) return;
        showOriginal = show;
        if (!show || original != null || "loading".equals(originalStatus)) {
            changed.run();
            return;
        }
        originalStatus = "loading";
        changed.run();
        int ticket = request;
        String source = address;
        String articleId = id;
        job = worker.submit(() -> {
            try {
                JSONObject article = BlizzardArticle.fetchOriginal(source, articleId);
                main.post(() -> {
                    if (request != ticket) return;
                    original = article;
                    originalStatus = "ready";
                    save();
                    if (showOriginal) changed.run();
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    if (request != ticket) return;
                    originalStatus = "failed";
                    if (showOriginal) changed.run();
                });
            }
        });
    }

    private boolean needsTranslation() {
        return body != null && "original".equals(body.optString("translation"));
    }

    void translate() {
        if (!needsTranslation() || "translating".equals(status)) return;
        if (translator == null) translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.RUSSIAN).build());
        Translator engine = translator;
        JSONObject article = body;
        int ticket = request;
        status = "translating";
        changed.run();
        job = worker.submit(() -> {
            try {
                Tasks.await(engine.downloadModelIfNeeded(new DownloadConditions.Builder().build()));
                JSONArray source = article.getJSONArray("blocks");
                JSONArray translated = new JSONArray();
                for (int i = 0; i < source.length(); i++) {
                    JSONObject block = source.getJSONObject(i);
                    translated.put(new JSONObject().put("type", block.getString("type"))
                        .put("depth", block.optInt("depth")).put("text", translateText(engine, block.getString("text"))));
                }
                JSONObject result = new JSONObject().put("translation", "machine").put("blocks", translated);
                main.post(() -> {
                    if (request != ticket) return;
                    original = article;
                    body = result;
                    save();
                    status = "ready";
                    changed.run();
                });
            } catch (Exception unavailable) {
                main.post(() -> failed(ticket, "translation-failed"));
            }
        });
    }

    static String translateText(Translator engine, String text) throws Exception {
        StringBuilder result = new StringBuilder();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + TRANSLATION_CHUNK, text.length());
            if (end < text.length()) {
                int space = text.lastIndexOf(' ', end);
                if (space > start) end = space;
                else if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                String value = Tasks.await(engine.translate(chunk)).trim();
                if (value.isEmpty()) throw new IllegalStateException("Empty translation");
                if (result.length() > 0) result.append(' ');
                result.append(value);
            }
            start = end;
        }
        return result.toString();
    }

    private void save() {
        try {
            cache.put(version, new JSONObject().put("version", version).put("body", body).put("original", original));
            if (cache.size() > CACHE_SIZE) cache.remove(cache.keySet().iterator().next());
            JSONArray entries = new JSONArray();
            for (JSONObject entry : cache.values()) entries.put(entry);
            preferences.edit().putString(CACHE, entries.toString()).apply();
        } catch (JSONException invalid) {
            // The opened article remains usable in memory.
        }
    }

    private void failed(int ticket, String failure) {
        if (request != ticket) return;
        status = failure;
        changed.run();
    }

    private void cancel() {
        request++;
        if (job != null) job.cancel(true);
        job = null;
    }

    void collapse() {
        if (id == null) return;
        cancel();
        id = null;
        body = null;
        original = null;
        changed.run();
    }

    void close() {
        cancel();
        worker.shutdownNow();
        if (translator != null) translator.close();
    }
}
