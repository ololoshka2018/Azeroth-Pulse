package ru.ivan.azerothpulse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

/** Downloads public data and reads census observations. */
final class ForeverSources {
    static final String CENSUS_URL = "https://wowcensus.io/realms";
    static final String[] REGIONS = {"EU", "US"};
    static final String[] MODES = {"PVE", "PVP", "RP", "RPPVP"};
    private static final Instant LAUNCH = Instant.parse("2026-11-04T23:00:00Z");
    private static final DateTimeFormatter SCAN_DATE = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US);
    private static final Pattern STAMP = Pattern.compile("Scanned: ([A-Za-z]{3} \\d{1,2}, \\d{4}) · Page generated: (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})Z");
    private static final Pattern ROUTE = Pattern.compile("window\\.ROUTE\\s*=\\s*");
    private static final Pattern REALMS = Pattern.compile("const\\s+REALMS\\s*=\\s*");
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;

    private ForeverSources() {}

    static String download(String address) throws IOException {
        return downloadPage(address, null, 0, "text/html");
    }

    static String downloadJson(String address) throws IOException {
        return downloadPage(address, null, 0, "application/vnd.github+json");
    }

    static String downloadArticle(String address, String id) throws IOException {
        Predicate<URI> sameArticle = uri -> id.equals(BlizzardNews.articleId(uri));
        if (!sameArticle.test(URI.create(address))) throw new IOException("Unknown article");
        return downloadPage(address, sameArticle, 2, "text/html");
    }

    private static String downloadPage(String address, Predicate<URI> allowRedirect, int redirects, String accept) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(address).openConnection();
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "AzerothPulse/0.17 (Forever companion)");
        try {
            int status = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            if (status >= 300 && status < 400 && redirects > 0 && allowRedirect != null && location != null) {
                URI target = URI.create(address).resolve(location);
                if (!allowRedirect.test(target)) throw new IOException("Unexpected redirect");
                return downloadPage(target.toASCIIString(), allowRedirect, redirects - 1, accept);
            }
            if (status == 404) throw new java.io.FileNotFoundException("Source not published");
            if (status != 200) throw new IOException("Source did not return a page");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (output.size() + size > MAX_PAGE_BYTES) throw new IOException("Source page is too large");
                    output.write(buffer, 0, size);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    static JSONObject census(String html, String region) throws Exception {
        Document page = Jsoup.parse(html);
        Element stamp = page.selectFirst(".stamp");
        if (stamp == null) throw new IOException("No census stamp");
        Matcher timestamp = STAMP.matcher(stamp.text());
        if (!timestamp.find()) throw new IOException("Unknown census date format");
        LocalDate scanDate = LocalDate.parse(timestamp.group(1), SCAN_DATE);
        Instant published = Instant.parse(timestamp.group(2).replace(' ', 'T') + ":00Z");
        if (scanDate.isBefore(LocalDate.parse("2026-11-04")) || published.isBefore(LAUNCH)) {
            throw new IOException("Pre-launch census");
        }

        JSONObject route = null;
        JSONArray rows = null;
        for (Element script : page.select("script:not([src])")) {
            String code = script.data();
            Matcher routeMatch = ROUTE.matcher(code);
            if (routeMatch.find()) route = (JSONObject) new JSONTokener(code.substring(routeMatch.end())).nextValue();
            Matcher realmMatch = REALMS.matcher(code);
            if (realmMatch.find()) rows = (JSONArray) new JSONTokener(code.substring(realmMatch.end())).nextValue();
        }
        if (route == null || !"forever".equals(route.optString("edition"))
                || !"realms".equals(route.optString("page")) || rows == null) {
            throw new IOException("Unknown census edition or layout");
        }

        JSONObject realms = new JSONObject();
        String idPrefix = region.toLowerCase(Locale.ROOT) + ":";
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (!region.equals(row.optString("groupRegion"))) continue;
            String mode = modeId(row.optString("realmType"));
            if (mode == null || !row.optString("id").startsWith(idPrefix) || !row.optBoolean("known")) continue;
            if (realms.has(mode)) throw new IOException("Duplicate ruleset");
            long total = row.optLong("seen", -1);
            if (total < 1 || total > 100_000_000) continue;
            JSONObject realm = new JSONObject();
            realm.put("total", total);
            realm.put("active7d", boundedCount(row, "d7", total));
            realm.put("active24h", boundedCount(row, "d24", total));
            realm.put("alliance", boundedCount(row, "A", total));
            realm.put("horde", boundedCount(row, "H", total));
            realm.put("classes", percentages(row.optJSONObject("cls")));
            realm.put("classesAlliance", percentages(row.optJSONObject("clsA")));
            realm.put("classesHorde", percentages(row.optJSONObject("clsH")));
            realm.put("races", percentages(row.optJSONObject("race")));
            realm.put("scanDate", scanDate.toString());
            realm.put("publishedAt", published.toString());
            realms.put(mode, realm);
        }
        if (realms.length() == 0) throw new IOException("No census groups for " + region);
        return realms;
    }

    private static Object boundedCount(JSONObject row, String key, long total) {
        long count = row.optLong(key, -1);
        return count >= 0 && count <= total ? count : JSONObject.NULL;
    }

    private static JSONObject percentages(JSONObject values) throws Exception {
        JSONObject result = new JSONObject();
        if (values == null) return result;
        for (java.util.Iterator<String> keys = values.keys(); keys.hasNext();) {
            String key = keys.next();
            double share = values.optDouble(key, -1);
            if (share >= 0 && share <= 100 && key.length() <= 40) result.put(key, share);
        }
        return result;
    }

    private static String modeId(String label) {
        switch (label.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "")) {
            case "PVE": case "NORMAL": return "PVE";
            case "PVP": return "PVP";
            case "RP": return "RP";
            case "RPPVP": return "RPPVP";
            default: return null;
        }
    }

    /** Keeps the newest valid page for each ruleset, independently of other requests. */
    static void merge(JSONObject destination, JSONObject incoming, String stampKey) throws Exception {
        for (String mode : MODES) {
            JSONObject update = incoming.optJSONObject(mode);
            if (update == null) continue;
            Instant updateTime = Instant.parse(update.getString(stampKey));
            JSONObject old = destination.optJSONObject(mode);
            if (old != null && updateTime.isBefore(Instant.parse(old.getString(stampKey)))) continue;
            destination.put(mode, update);
        }
    }
}
