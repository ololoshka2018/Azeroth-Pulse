package ru.ivan.azerothpulse;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

final class DropsBoard {
    private static final String URL = "https://raw.githubusercontent.com/" + AppUpdates.REPOSITORY
        + "/main/app/src/main/assets/drops.json";
    private static final String CACHE = "twitchDropsV1";
    private static final long INTERVAL = 30 * 60 * 1000L;

    private final SharedPreferences preferences;
    private final Executor network;
    private final Handler main;
    private final Runnable changed;
    private JSONObject catalog = new JSONObject();
    private long lastAttempt = -INTERVAL;
    private boolean loading;
    private boolean offline;
    private boolean closed;

    DropsBoard(SharedPreferences preferences, Executor network, Handler main, Runnable changed, String seed) {
        this.preferences = preferences;
        this.network = network;
        this.main = main;
        this.changed = changed;
        useSaved(seed);
        useSaved(preferences.getString(CACHE, ""));
    }

    JSONObject state() throws JSONException {
        return new JSONObject().put("campaigns", campaigns()).put("offline", offline);
    }

    private JSONArray campaigns() {
        JSONArray saved = catalog.optJSONArray("campaigns");
        return saved == null ? new JSONArray() : saved;
    }

    private void useSaved(String encoded) {
        try {
            JSONObject saved = parse(encoded);
            if (atLeastCurrent(saved)) catalog = saved;
        } catch (Exception invalid) { /* Keep the last valid catalog. */ }
    }

    private boolean atLeastCurrent(JSONObject candidate) {
        return !Instant.parse(candidate.optString("updatedAt"))
            .isBefore(Instant.parse(catalog.optString("updatedAt", Instant.EPOCH.toString())));
    }

    void refresh(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (closed || loading || (!force && now - lastAttempt < INTERVAL)) return;
        lastAttempt = now;
        loading = true;
        network.execute(() -> {
            JSONObject downloaded;
            try { downloaded = parse(ForeverSources.downloadJson(URL)); }
            catch (Exception unavailable) { downloaded = null; }
            JSONObject result = downloaded;
            main.post(() -> {
                if (closed) return;
                loading = false;
                offline = result == null;
                if (result != null && atLeastCurrent(result)) {
                    catalog = result;
                    preferences.edit().putString(CACHE, catalog.toString()).apply();
                }
                changed.run();
            });
        });
    }

    String source(String id) {
        JSONArray campaigns = campaigns();
        for (int i = 0; i < campaigns.length(); i++) {
            JSONObject campaign = campaigns.optJSONObject(i);
            if (campaign.optString("id").equals(id)) return campaign.optString("source");
        }
        return null;
    }

    static JSONObject parse(String encoded) throws JSONException {
        if (encoded.length() > 128_000) throw new IllegalArgumentException("Oversized Drops catalog");
        JSONObject result = new JSONObject(encoded);
        Instant.parse(result.getString("updatedAt"));
        JSONArray campaigns = result.getJSONArray("campaigns");
        Set<String> ids = new HashSet<>();
        if (campaigns.length() > 64) throw new IllegalArgumentException("Too many Drops campaigns");
        for (int i = 0; i < campaigns.length(); i++) {
            JSONObject campaign = campaigns.getJSONObject(i);
            String id = campaign.getString("id");
            String type = campaign.getString("type");
            int minutes = campaign.optInt("minutes");
            Instant start = Instant.parse(campaign.getString("startsAt"));
            Instant end = Instant.parse(campaign.getString("endsAt"));
            URI source = URI.create(campaign.getString("source"));
            boolean sourceAllowed = "https".equals(source.getScheme()) && source.getUserInfo() == null
                && source.getPort() == -1 && ("worldofwarcraft.blizzard.com".equals(source.getHost())
                    || "blog.twitch.tv".equals(source.getHost()));
            if (!id.matches("[a-z0-9-]{1,64}") || !ids.add(id) || campaign.getString("title").trim().isEmpty()
                    || campaign.getString("description").length() > 600 || !end.isAfter(start) || !sourceAllowed
                    || !("subscription".equals(type) || ("watch".equals(type) && minutes > 0 && minutes <= 1440))) {
                throw new IllegalArgumentException("Invalid Drops campaign");
            }
        }
        return result;
    }

    void close() { closed = true; }
}
