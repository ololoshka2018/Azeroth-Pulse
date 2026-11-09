package ru.ivan.azerothpulse;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Optional owner-configured OAuth integration; no Blizzard secret exists in the APK. */
final class AccountBoard {
    private final Activity activity;
    private final SharedPreferences preferences;
    private final ExecutorService worker;
    private final Handler main;
    private final Runnable changed;
    private final String base;
    private String token = "", status = "signed-out", error = "";
    private JSONObject account;
    private boolean closed;
    private int request;

    AccountBoard(Activity activity, SharedPreferences preferences, ExecutorService worker, Handler main, Runnable changed) {
        this.activity = activity; this.preferences = preferences; this.worker = worker; this.main = main; this.changed = changed;
        String configured = BuildConfig.BATTLENET_SERVICE_URL.replaceAll("/+$", "");
        String safe = "";
        try {
            URI uri = URI.create(configured);
            if ("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getPort() == -1 && (uri.getPath().isEmpty() || "/".equals(uri.getPath())) && uri.getQuery() == null && uri.getFragment() == null) safe = configured;
        } catch (Exception invalid) { /* Unconfigured builds retain public profiles. */ }
        base = safe;
        if (!base.isEmpty()) try {
            String encoded = preferences.getString("accountSessionV1", "");
            if (!encoded.isEmpty()) {
                JSONObject sealed = new JSONObject(encoded);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(sealed.getString("iv"), Base64.NO_WRAP)));
                token = new String(cipher.doFinal(Base64.decode(sealed.getString("data"), Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
                account = new JSONObject(preferences.getString("accountDetailsV1", "{}")); status = "saved";
            }
        } catch (Exception invalid) { clear(); }
    }
    JSONObject state() throws Exception { return new JSONObject().put("configured", !base.isEmpty()).put("status", status).put("account", account).put("error", error); }
    private static String random() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return encode(value); }
    private static String encode(byte[] value) { return Base64.encodeToString(value, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE); }
    void login(String region) {
        if (closed || base.isEmpty() || !("EU".equals(region) || "US".equals(region))) return;
        try {
            String verifier = random(), nonce = random();
            String challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            preferences.edit().putString("oauthVerifierV1", verifier).putString("oauthStateV1", nonce).putLong("oauthStartedV1", System.currentTimeMillis()).apply();
            Uri destination = Uri.parse(base + "/auth/start").buildUpon().appendQueryParameter("region", region)
                .appendQueryParameter("challenge", challenge).appendQueryParameter("state", nonce).build();
            status = "signing-in"; error = ""; changed.run();
            activity.startActivity(new Intent(Intent.ACTION_VIEW, destination));
        } catch (Exception unavailable) { fail("Не удалось открыть вход Battle.net."); }
    }
    void callback(Uri uri) {
        if (closed || base.isEmpty() || uri == null || !"azerothpulse".equals(uri.getScheme()) || !"oauth".equals(uri.getHost()) || !"/callback".equals(uri.getPath())) return;
        String nonce = preferences.getString("oauthStateV1", ""), verifier = preferences.getString("oauthVerifierV1", "");
        long age = System.currentTimeMillis() - preferences.getLong("oauthStartedV1", 0);
        if (nonce.isEmpty() || !nonce.equals(uri.getQueryParameter("state")) || age < 0 || age > 600000) return;
        preferences.edit().remove("oauthStateV1").remove("oauthVerifierV1").remove("oauthStartedV1").apply();
        String code = uri.getQueryParameter("code");
        if (uri.getQueryParameter("error") != null || code == null || !code.matches("[A-Za-z0-9_-]{43}")) { fail("Вход отменён. Можно повторить."); return; }
        int ticket = ++request; status = "loading"; changed.run();
        worker.execute(() -> {
            try {
                JSONObject response = exchange("/auth/redeem", "POST", new JSONObject().put("code", code).put("verifier", verifier), "");
                String session = response.getString("session");
                if (!session.matches("[A-Za-z0-9_-]{43}")) throw new Exception();
                main.post(() -> {
                    if (closed || ticket != request) return;
                    try {
                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
                        String encrypted = new JSONObject().put("iv", Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                            .put("data", Base64.encodeToString(cipher.doFinal(session.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP)).toString();
                        token = session; account = response.getJSONObject("account"); status = "checked";
                        preferences.edit().putString("accountSessionV1",encrypted).putString("accountDetailsV1",account.toString()).apply(); changed.run();
                    } catch (Exception invalid) { clear(); fail("Не удалось безопасно сохранить вход. Повтори попытку."); }
                });
            } catch (Exception unavailable) { main.post(() -> { if (!closed && ticket == request) fail("Вход не завершён. Проверь подключение и повтори."); }); }
        });
    }
    void refresh() {
        if (closed || base.isEmpty() || token.isEmpty() || "loading".equals(status)) return;
        int ticket = ++request; String session = token; status = "loading"; changed.run();
        worker.execute(() -> {
            try {
                JSONObject result = exchange("/account", "GET", null, session);
                main.post(() -> { if (!closed && ticket == request) { account = result; status = "checked"; preferences.edit().putString("accountDetailsV1",account.toString()).apply(); changed.run(); } });
            } catch (Exception unavailable) { main.post(() -> {
                if (closed || ticket != request) return;
                if (unavailable instanceof java.io.FileNotFoundException) clear();
                fail("Список персонажей не обновился. При завершении сеанса войди снова.");
            }); }
        });
    }
    void logout() {
        if (closed) return;
        request++; String session = token; clear(); changed.run();
        if (!session.isEmpty()) worker.execute(() -> { try { exchange("/session","DELETE",null,session); } catch (Exception ignored) {} });
    }
    private JSONObject exchange(String path, String method, JSONObject body, String token) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new java.net.URL(base + path).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method); connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept","application/json");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization","Bearer " + token);
        try {
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type","application/json");
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            if (status == 401) throw new java.io.FileNotFoundException();
            if (status != 200) throw new java.io.IOException("Account service unavailable");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[8192]; int n;
                while ((n = in.read(bytes)) != -1) { if (out.size() + n > 2 * 1024 * 1024) throw new java.io.IOException(); out.write(bytes,0,n); }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
    private SecretKey key() throws Exception {
        String alias = "azeroth-pulse-account-v1"; KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias,null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    private void clear() { token = ""; account = null; status = "signed-out"; error = ""; preferences.edit().remove("accountSessionV1").remove("accountDetailsV1").apply(); }
    private void fail(String message) { status = "failed"; error = message; changed.run(); }
    void close() { closed = true; request++; }
}
