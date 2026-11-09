package ru.ivan.azerothpulse;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Downloads only the selected release, then lets Android approve the verified APK. */
final class ApkUpdater {
    private final Activity activity;
    private final ExecutorService worker;
    private final Handler main;
    private final Runnable changed;
    private final File apk;
    private String status = "idle", error = "";
    private int progress;
    private JSONObject release;
    private boolean closed;
    private volatile int generation;

    ApkUpdater(Activity activity, ExecutorService worker, Handler main, Runnable changed) {
        this.activity = activity; this.worker = worker; this.main = main; this.changed = changed;
        apk = new File(activity.getCacheDir(), "updates/update.apk");
    }

    JSONObject state() throws Exception {
        return new JSONObject().put("status", status).put("progress", progress).put("error", error);
    }

    void download(JSONObject source) {
        if (closed || source == null || "downloading".equals(status) || "verifying".equals(status)) return;
        try {
            if (AppUpdates.compare(source.getString("version"), BuildConfig.VERSION_NAME) <= 0) return;
            if (!source.optString("sha256").matches("[0-9a-f]{64}")) throw new Exception();
            release = new JSONObject(source.toString());
        } catch (Exception invalid) { fail("В выпуске отсутствуют проверяемые сведения об APK."); return; }
        int ticket = ++generation;
        JSONObject expected = release;
        status = "downloading"; error = ""; progress = 0; changed.run();
        worker.execute(() -> {
            File partial = new File(apk.getParentFile(), "download-" + ticket + ".part");
            try {
                if (!apk.getParentFile().isDirectory() && !apk.getParentFile().mkdirs()) throw new Exception();
                transfer(expected, partial, ticket);
                main.post(() -> { if (!closed && ticket == generation) { status = "verifying"; changed.run(); } });
                verify(partial, expected);
                if (closed || ticket != generation) return;
                if (apk.exists() && !apk.delete()) throw new Exception();
                if (!partial.renameTo(apk)) throw new Exception();
                main.post(() -> { if (!closed && ticket == generation) { status = "ready"; progress = 100; changed.run(); install(); } });
            } catch (Exception failure) {
                main.post(() -> { if (!closed && ticket == generation) fail("Загрузка или проверка APK не завершена. Проверь интернет и свободное место, затем повтори."); });
            } finally { if (partial.exists()) partial.delete(); }
        });
    }

    private void transfer(JSONObject expected, File target, int ticket) throws Exception {
        long length = expected.getLong("size");
        if (length < 1 || length > 200L * 1024 * 1024) throw new Exception("Invalid APK size");
        URI address = URI.create(expected.getString("download"));
        for (int redirects = 0; redirects < 5; redirects++) {
            if (!allowed(address)) throw new Exception("Untrusted download host");
            HttpsURLConnection connection = (HttpsURLConnection) address.toURL().openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "AzerothPulse/0.18");
            try {
                int response = connection.getResponseCode();
                if (response >= 300 && response < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new Exception();
                    address = address.resolve(location); continue;
                }
                if (response != 200) throw new Exception("Download unavailable");
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[32768]; long count = 0; int read, last = -1;
                    while ((read = input.read(buffer)) != -1) {
                        if (closed || ticket != generation || Thread.currentThread().isInterrupted()) throw new Exception("Cancelled");
                        count += read; if (count > length) throw new Exception("Oversize download");
                        output.write(buffer, 0, read);
                        int percent = (int) (count * 100 / length);
                        if (percent != last) {
                            last = percent;
                            main.post(() -> { if (!closed && ticket == generation) { progress = percent; changed.run(); } });
                        }
                    }
                    output.getFD().sync();
                    if (count != length) throw new Exception("Incomplete download");
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new Exception("Too many redirects");
    }

    static boolean allowed(URI uri) {
        String host = uri.getHost();
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
            && ("github.com".equals(host) || "release-assets.githubusercontent.com".equals(host)
                || "objects.githubusercontent.com".equals(host));
    }

    private void verify(File file, JSONObject expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        if (file.length() != expected.getLong("size") || !hex(digest.digest()).equals(expected.getString("sha256"))) throw new Exception("Checksum mismatch");
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (info == null || !activity.getPackageName().equals(info.packageName) || !expected.getString("version").equals(info.versionName)) throw new Exception("Wrong package");
        long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        if (version <= BuildConfig.VERSION_CODE) throw new Exception("Older version");
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : info.signatures;
        JSONObject pins;
        try (InputStream input = activity.getAssets().open("signers.json")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] b = new byte[2048]; int n;
            while ((n = input.read(b)) != -1) out.write(b, 0, n);
            pins = new JSONObject(out.toString("UTF-8"));
        }
        String pin = pins.getString(Build.VERSION.SDK_INT >= 28 ? "releaseSha256" : "legacySha256");
        if (signatures == null || signatures.length != 1 || !pin.equals(hex(digest.digest(signatures[0].toByteArray())))) throw new Exception("Unexpected signer");
    }

    static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    void install() {
        if (closed || release == null || !("ready".equals(status) || "permission".equals(status))) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            status = "permission"; changed.run();
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
            catch (android.content.ActivityNotFoundException unavailable) { fail("Разреши установку обновлений для Azeroth Pulse в настройках Android."); }
            return;
        }
        status = "verifying"; changed.run();
        JSONObject expected = release;
        worker.execute(() -> {
            try {
                verify(apk, expected);
                main.post(() -> {
                    if (closed) return;
                    status = "ready"; changed.run();
                    Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updates/update.apk");
                    Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { activity.startActivity(intent); }
                    catch (android.content.ActivityNotFoundException unavailable) { fail("Системный установщик Android недоступен."); }
                });
            } catch (Exception invalid) { main.post(() -> { if (!closed) fail("Проверка файла не пройдена. Скачай обновление повторно."); }); }
        });
    }

    void resume() { if ("permission".equals(status) && activity.getPackageManager().canRequestPackageInstalls()) install(); }
    void cancel() { generation++; status = "idle"; changed.run(); }
    private void fail(String message) { status = "failed"; error = message; changed.run(); }
    void close() { closed = true; generation++; }
}
