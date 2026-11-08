package ru.ivan.azerothpulse;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + APP_HOST + "/ui/index.html";
    private static final String CACHE = "snapshotV2";
    private static final long TALENT_REFRESH_INTERVAL = 30 * 60 * 1000L;
    private final ExecutorService network = Executors.newFixedThreadPool(5);
    private final Map<String, RegionState> regions = new HashMap<>();
    private final Map<String, Long> talentAttempts = new HashMap<>();
    private final java.util.Set<String> talentsLoading = new java.util.HashSet<>();
    private SharedPreferences preferences;
    private SharedPreferences talentCache;
    private WebView screen;
    private NewsBoard news;
    private AppUpdates appUpdates;
    private TalentChanges talentChanges;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable talentsTick = new Runnable() {
        @Override public void run() {
            String selected = preferences.getString("talentClass", "Rogue");
            refreshTalentCatalog(selected, false);
            for (String name : TalentStats.CLASSES) {
                if (!selected.equals(name)) refreshTalentCatalog(name, false);
            }
            main.postDelayed(this, TALENT_REFRESH_INTERVAL);
        }
    };
    private JSONObject talentStats;
    private JSONObject talentSeed;
    private String talentStatsStatus = "saved";
    private boolean checkedTalentStats;
    private volatile String uiState;
    private String region;
    private boolean closed;

    private final class RegionState {
        final String id;
        final JSONObject snapshot;
        final JSONObject sourceStatus = new JSONObject();
        String favorite;
        String status = "saved";

        RegionState(String id) {
            this.id = id;
            snapshot = readSnapshot(id);
            favorite = preferences.getString(favoriteKey(), "PVE");
        }

        String favoriteKey() { return "EU".equals(id) ? "favorite" : "favorite." + id; }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("pulse", MODE_PRIVATE);
        talentCache = getSharedPreferences("talentCatalogV1", MODE_PRIVATE);
        talentChanges = new TalentChanges(talentCache);
        appUpdates = new AppUpdates(preferences, network, main, this::publishState, BuildConfig.VERSION_NAME);
        try { talentSeed = new JSONObject(assetText("ui/talents-data.json")); }
        catch (Exception unavailable) { talentSeed = new JSONObject(); }
        try { talentStats = new JSONObject(preferences.getString("talentStatsV1", "{}")); }
        catch (Exception invalid) { talentStats = new JSONObject(); }
        for (String id : ForeverSources.REGIONS) regions.put(id, new RegionState(id));
        region = preferences.getString("region", "EU");
        if (!regions.containsKey(region)) region = "EU";
        news = new NewsBoard(preferences, readNewsSeed(), this::publishState);
        // Retire the background task and any notification from older versions.
        getSystemService(android.app.job.JobScheduler.class).cancel(1203);
        getSystemService(android.app.NotificationManager.class).cancel(1204);
        updateState();
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(0xff100d0a);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            container.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        screen = new WebView(this);
        screen.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = screen.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        screen.addJavascriptInterface(new AppActions(), "Pulse");
        screen.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && APP_HOST.equals(uri.getHost())) {
                    WebResourceResponse response = localAsset(uri.getPath());
                    if (response != null) return response;
                }
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                    Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
        });
        container.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        setContentView(container);
        screen.loadUrl(APP_URL);
    }

    private WebResourceResponse localAsset(String path) {
        if (path == null) return null;
        String type;
        switch (path) {
            case "/ui/index.html": type = "text/html"; break;
            case "/ui/theme.css": type = "text/css"; break;
            case "/ui/app.js": type = "application/javascript"; break;
            case "/ui/talents.js": type = "application/javascript"; break;
            case "/ui/talents-data.json": type = "application/json"; break;
            case "/ui/classic/morpheus.ttf": type = "font/ttf"; break;
            default:
                if (!path.matches("/ui/classic/[a-z0-9-]+\\.png")) return null;
                type = "image/png";
        }
        try {
            String encoding = type.equals("image/png") || type.equals("font/ttf") ? null : "UTF-8";
            return new WebResourceResponse(type, encoding, getAssets().open(path.substring(1)));
        } catch (IOException missing) {
            return null;
        }
    }

    private String assetText(String name) throws IOException {
        try (InputStream input = getAssets().open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int length;
            while ((length = input.read(bytes)) != -1) output.write(bytes, 0, length);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private JSONArray readNewsSeed() {
        try { return new JSONArray(assetText("news-seed.json")); }
        catch (Exception invalid) { return new JSONArray(); }
    }

    private static String cacheKey(String region) { return "EU".equals(region) ? CACHE : CACHE + "." + region; }

    private JSONObject readSnapshot(String region) {
        try {
            String asset = "EU".equals(region) ? "seed.json" : "seed-us.json";
            JSONObject seed = new JSONObject(assetText(asset));
            markOrigin(seed, "initial");
            String cached = preferences.getString(cacheKey(region), null);
            if (cached == null) return seed;
            try {
                JSONObject previous = new JSONObject(cached);
                markOrigin(previous, "saved");
                ForeverSources.merge(seed.getJSONObject("realms"), previous.getJSONObject("realms"), "publishedAt");
            } catch (Exception ignored) {
                // A damaged cache cannot prevent loading the bundled observations.
            }
            return seed;
        } catch (Exception invalidSeed) {
            throw new IllegalStateException("Invalid bundled census", invalidSeed);
        }
    }

    private static void markOrigin(JSONObject snapshot, String origin) throws JSONException {
        JSONObject entries = snapshot.getJSONObject("realms");
        for (java.util.Iterator<String> keys = entries.keys(); keys.hasNext();) {
            entries.getJSONObject(keys.next()).put("origin", origin);
        }
    }

    private void updateState() {
        try {
            RegionState current = regions.get(region);
            uiState = new JSONObject().put("region", region).put("snapshot", current.snapshot)
                .put("favorite", current.favorite).put("status", current.status).put("news", news.state())
                .put("sourceStatus", current.sourceStatus).put("appUpdates", appUpdates.state())
                .put("talentChanges", talentChanges.state())
                .put("talentStats", talentStats).put("talentStatsStatus", talentStatsStatus).toString();
        } catch (Exception invalidState) {
            throw new IllegalStateException(invalidState);
        }
    }

    private void publishState() {
        updateState();
        screen.evaluateJavascript("window.acceptPulseState && window.acceptPulseState(" + JSONObject.quote(uiState) + ")", null);
    }

    private void refresh() {
        RegionState current = regions.get(region);
        if (closed || "loading".equals(current.status)) return;
        current.status = "loading";
        try {
            current.sourceStatus.put("census", "loading");
            for (String mode : ForeverSources.MODES) {
                current.sourceStatus.put("census." + mode, "loading");
            }
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        publishState();
        request(current);
    }

    private void refreshTalents(boolean force) {
        if (closed || "loading".equals(talentStatsStatus) || (!force && checkedTalentStats)) return;
        checkedTalentStats = true;
        talentStatsStatus = "loading";
        publishState();
        network.execute(() -> {
            JSONObject result;
            try { result = TalentStats.parse(ForeverSources.download(TalentStats.URL)); }
            catch (Exception unavailable) { result = null; }
            JSONObject snapshot = result;
            runOnUiThread(() -> {
                if (closed) return;
                boolean accepted = snapshot != null && snapshot.optString("observedAt").compareTo(talentStats.optString("observedAt")) >= 0;
                if (accepted) {
                    talentStats = snapshot;
                    try { talentStats.put("verifiedAt", Instant.now().toString()).put("origin", "network"); }
                    catch (JSONException invalid) { throw new IllegalStateException(invalid); }
                    preferences.edit().putString("talentStatsV1", snapshot.toString()).apply();
                }
                talentStatsStatus = snapshot == null ? "offline" : accepted ? "checked" : "saved";
                publishState();
            });
        });
    }

    private void refreshTalentCatalog(String name, boolean force) {
        if (closed || !TalentStats.isClass(name) || talentsLoading.contains(name)) return;
        long now = SystemClock.elapsedRealtime();
        Long last = talentAttempts.get(name);
        if (!force && last != null && now - last < TALENT_REFRESH_INTERVAL) return;
        long savedAttempt = talentCache.getLong("attempt." + name, 0);
        long age = System.currentTimeMillis() - savedAttempt;
        if (!force && last == null && age >= 0 && age < TALENT_REFRESH_INTERVAL) return;
        talentAttempts.put(name, now);
        talentCache.edit().putLong("attempt." + name, System.currentTimeMillis()).apply();
        talentsLoading.add(name);
        publishTalentCatalog(name, "loading", null);
        network.execute(() -> {
            JSONObject result;
            JSONObject previous = null;
            try {
                JSONObject seed = talentSeed.getJSONObject("classes").getJSONObject(name);
                try { previous = new JSONObject(talentCache.getString(name, seed.toString())); }
                catch (Exception invalidCache) { previous = seed; }
                if (!previous.has("version")) previous.put("version", talentSeed.getString("version"));
                result = TalentCatalog.refresh(name, seed, talentSeed.getString("version"), previous, ForeverSources::download);
            } catch (Exception unavailable) {
                result = null;
            }
            JSONObject catalog = result;
            JSONObject before = previous;
            runOnUiThread(() -> {
                talentsLoading.remove(name);
                if (closed) return;
                if (catalog != null) {
                    try { talentChanges.saveCatalog(name, before, catalog); }
                    catch (JSONException invalid) { talentCache.edit().putString(name, catalog.toString()).apply(); }
                }
                String status = catalog == null ? "offline" : catalog.optBoolean("partial") ? "partial" : "checked";
                publishTalentCatalog(name, status, catalog);
                publishState();
            });
        });
    }

    private void publishTalentCatalog(String name, String status, JSONObject catalog) {
        String payload = catalog == null ? "null" : JSONObject.quote(catalog.toString());
        screen.evaluateJavascript("window.acceptTalentCatalog && window.acceptTalentCatalog("
            + JSONObject.quote(name) + "," + JSONObject.quote(status) + "," + payload + ")", null);
    }

    private void request(RegionState target) {
        network.execute(() -> {
            JSONObject result;
            try {
                result = ForeverSources.census(ForeverSources.download(ForeverSources.CENSUS_URL), target.id);
            } catch (Exception failure) {
                result = null;
            }
            JSONObject completed = result;
            runOnUiThread(() -> receive(target, completed));
        });
    }

    private void receive(RegionState target, JSONObject result) {
        if (closed) return;
        String checkedAt = Instant.now().toString();
        boolean accepted = result != null;
        if (accepted) {
            try {
                for (java.util.Iterator<String> keys = result.keys(); keys.hasNext();) {
                    result.getJSONObject(keys.next()).put("verifiedAt", checkedAt).put("origin", "network");
                }
                ForeverSources.merge(target.snapshot.getJSONObject("realms"), result, "publishedAt");
                preferences.edit().putString(cacheKey(target.id), target.snapshot.toString()).apply();
            } catch (Exception invalid) {
                accepted = false;
            }
        }
        int checked = 0;
        try {
            JSONObject entries = target.snapshot.getJSONObject("realms");
            for (String mode : ForeverSources.MODES) {
                JSONObject entry = entries.optJSONObject(mode);
                String status = !accepted ? "offline" :
                    entry != null && checkedAt.equals(entry.optString("verifiedAt")) ? "checked" : "saved";
                if ("checked".equals(status)) checked++;
                target.sourceStatus.put("census." + mode, status);
            }
            target.status = !accepted ? "offline" : checked == ForeverSources.MODES.length ? "checked" : "partial";
            target.sourceStatus.put("census", target.status);
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        if (region.equals(target.id)) publishState();
    }

    private final class AppActions {
        @JavascriptInterface public String initial() { return uiState; }

        @JavascriptInterface public void refresh() {
            runOnUiThread(MainActivity.this::refresh);
        }

        @JavascriptInterface public void refreshNews() { runOnUiThread(news::refresh); }

        @JavascriptInterface public void checkAppUpdates() { runOnUiThread(() -> appUpdates.check(true)); }

        @JavascriptInterface public void downloadAppUpdate() {
            runOnUiThread(() -> {
                if (closed) return;
                String address = appUpdates.download();
                if (address.isEmpty()) return;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address))); }
                catch (ActivityNotFoundException unavailable) {
                    android.widget.Toast.makeText(MainActivity.this, "Для скачивания нужен браузер.", android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface public void talentChangesViewed(long latest) {
            runOnUiThread(() -> {
                if (closed) return;
                talentChanges.viewed(latest);
                publishState();
            });
        }

        @JavascriptInterface public void refreshTalents(boolean force) { runOnUiThread(() -> MainActivity.this.refreshTalents(force)); }

        @JavascriptInterface public void refreshTalentCatalog(String name, boolean force) {
            runOnUiThread(() -> MainActivity.this.refreshTalentCatalog(name, force));
        }

        @JavascriptInterface public String cachedTalentCatalog(String name) {
            return TalentStats.isClass(name) ? talentCache.getString(name, "") : "";
        }

        @JavascriptInterface public String talentClass() { return preferences.getString("talentClass", "Rogue"); }

        @JavascriptInterface public int talentLevel(String name) {
            return TalentStats.isClass(name) ? preferences.getInt("talentLevel." + name, 9) : 9;
        }

        @JavascriptInterface public void saveTalents(String name, int level) {
            if (TalentStats.isClass(name) && level >= 9 && level <= 60) {
                preferences.edit().putString("talentClass", name).putInt("talentLevel." + name, level).apply();
            }
        }

        @JavascriptInterface public void newsViewed() { runOnUiThread(news::viewed); }

        @JavascriptInterface public void translateNews() { runOnUiThread(news::enableTranslation); }

        @JavascriptInterface public void readNews(String id) { runOnUiThread(() -> news.read(id)); }

        @JavascriptInterface public void closeNews() { runOnUiThread(news::closeArticle); }

        @JavascriptInterface public void showOriginal(boolean show) { runOnUiThread(() -> news.showOriginal(show)); }

        @JavascriptInterface public void translateArticle() { runOnUiThread(news::enableArticleTranslation); }

        @JavascriptInterface public void selectRegion(String id) {
            runOnUiThread(() -> {
                if (closed || !regions.containsKey(id) || region.equals(id)) return;
                region = id;
                preferences.edit().putString("region", id).apply();
                publishState();
            });
        }

        @JavascriptInterface public void favorite(String mode) {
            runOnUiThread(() -> {
                if (closed || !Arrays.asList(ForeverSources.MODES).contains(mode)) return;
                RegionState current = regions.get(region);
                current.favorite = mode;
                preferences.edit().putString(current.favoriteKey(), mode).apply();
                publishState();
            });
        }

    }

    @Override protected void onResume() {
        super.onResume();
        news.resume();
        publishState();
        main.removeCallbacks(talentsTick);
        if (talentSeed.optJSONObject("classes") != null) talentsTick.run();
        appUpdates.check(false);
        screen.evaluateJavascript("window.refreshVisibleTalents && window.refreshVisibleTalents()", null);
    }

    @Override protected void onPause() {
        news.pause();
        main.removeCallbacks(talentsTick);
        super.onPause();
    }

    @Override public void onBackPressed() {
        screen.evaluateJavascript("window.dismissPulseDialog && window.dismissPulseDialog()", handled -> {
            if (!closed && !"true".equals(handled)) super.onBackPressed();
        });
    }

    @Override protected void onDestroy() {
        closed = true;
        news.close();
        appUpdates.close();
        network.shutdownNow();
        if (screen != null) {
            screen.removeJavascriptInterface("Pulse");
            ((FrameLayout) screen.getParent()).removeView(screen);
            screen.destroy();
        }
        super.onDestroy();
    }
}
