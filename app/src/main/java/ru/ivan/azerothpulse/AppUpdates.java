package ru.ivan.azerothpulse;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class AppUpdates {
    static final String REPOSITORY = "ololoshka2018/Azeroth-Pulse";
    private static final String CACHE = "appReleaseV1";
    private static final String CHECKED = "appReleaseChecked";
    private static final long INTERVAL = 6 * 60 * 60 * 1000L;
    private final SharedPreferences preferences;
    private final ExecutorService worker;
    private final Handler main;
    private final Runnable changed;
    private final String installed;
    private JSONObject release;
    private String status = "saved";
    private long lastAttempt = -INTERVAL;
    private boolean closed;

    AppUpdates(SharedPreferences preferences, ExecutorService worker, Handler main, Runnable changed, String installed) {
        this.preferences = preferences;
        this.worker = worker;
        this.main = main;
        this.changed = changed;
        this.installed = installed;
        try {
            JSONObject cached = new JSONObject(preferences.getString(CACHE, "{}"));
            if (isVersion(cached.optString("version")) && validDownload(cached.optString("download"))) release = cached;
        } catch (JSONException invalid) {
            // A release can be checked again when its cache is unavailable.
        }
    }

    JSONObject state() throws JSONException {
        return new JSONObject().put("installed", installed).put("status", status)
            .put("release", release).put("available", release != null && compare(release.optString("version"), installed) > 0)
            .put("checkedAt", preferences.getString(CHECKED, ""));
    }

    void check(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (closed || "loading".equals(status) || (!force && now - lastAttempt < INTERVAL)) return;
        lastAttempt = now;
        status = "loading";
        changed.run();
        worker.execute(() -> {
            JSONObject result = null;
            String outcome;
            try {
                result = parse(new JSONObject(ForeverSources.downloadJson("https://api.github.com/repos/" + REPOSITORY + "/releases/latest")));
                outcome = "checked";
            } catch (FileNotFoundException unpublished) {
                outcome = "unpublished";
            } catch (Exception unavailable) {
                outcome = "offline";
            }
            JSONObject received = result;
            String nextStatus = outcome;
            main.post(() -> {
                if (closed) return;
                status = nextStatus;
                if (received != null) {
                    release = received;
                    preferences.edit().putString(CACHE, release.toString())
                        .putString(CHECKED, Instant.now().toString()).apply();
                } else if ("unpublished".equals(status)) {
                    release = null;
                    preferences.edit().remove(CACHE).putString(CHECKED, Instant.now().toString()).apply();
                }
                changed.run();
            });
        });
    }

    String download() {
        if (release == null || compare(release.optString("version"), installed) <= 0) return "";
        String address = release.optString("download");
        return validDownload(address) ? address : "";
    }

    void close() { closed = true; }

    static JSONObject parse(JSONObject raw) throws Exception {
        String tag = raw.getString("tag_name");
        String version = tag.replaceFirst("^v", "");
        if (!isVersion(version) || raw.optBoolean("draft") || raw.optBoolean("prerelease")) {
            throw new IOException("Unsupported app release");
        }
        JSONArray assets = raw.getJSONArray("assets");
        String filename = "Azeroth-Pulse-" + version + ".apk";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String address = asset.optString("browser_download_url");
            String expected = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + filename;
            if (!filename.equals(asset.optString("name")) || !expected.equals(address) || !validDownload(address)
                    || asset.optLong("size") <= 0 || !"uploaded".equals(asset.optString("state"))) continue;
            String published = raw.getString("published_at");
            Instant.parse(published);
            String notes = raw.optString("body");
            return new JSONObject().put("version", version).put("publishedAt", published).put("download", address)
                .put("notes", notes.substring(0, Math.min(16_000, notes.length())));
        }
        throw new IOException("Release has no compatible APK");
    }

    private static boolean validDownload(String address) {
        try {
            URI uri = URI.create(address);
            return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getQuery() == null && uri.getFragment() == null
                && uri.getRawPath().startsWith("/" + REPOSITORY + "/releases/download/") && uri.getRawPath().endsWith(".apk");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean isVersion(String value) { return value.matches("[0-9]{1,5}(\\.[0-9]{1,5}){2}"); }

    static int compare(String left, String right) {
        if (!isVersion(left) || !isVersion(right)) return 0;
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int order = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (order != 0) return order;
        }
        return 0;
    }
}
