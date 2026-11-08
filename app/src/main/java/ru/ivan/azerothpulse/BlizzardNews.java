package ru.ivan.azerothpulse;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

final class BlizzardNews {
    static final String EN = "https://worldofwarcraft.blizzard.com/en-gb/news/";
    static final String RU = "https://worldofwarcraft.blizzard.com/ru-ru/news/";
    private static final int LIMIT = 20;
    private static final Pattern WOW_PATH = Pattern.compile("^/(?:[a-z]{2}-[a-z]{2}/)?news/(\\d+)(?:/[^?#]*)?$");
    private static final Pattern BLIZZARD_PATH = Pattern.compile("^/[a-z]{2}-[a-z]{2}/article/(\\d+)(?:/[^?#]*)?$");
    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");
    private static final Pattern FOREVER = Pattern.compile("\\bforever\\b", Pattern.CASE_INSENSITIVE);

    private BlizzardNews() {}

    static JSONArray parse(String html, String address) throws Exception {
        Document page = Jsoup.parse(html, address);
        JSONArray items = new JSONArray();
        int valid = 0;
        for (Element card : page.select("article.NewsBlog")) {
            Element link = card.selectFirst("a.NewsBlog-link[href]");
            Element date = card.selectFirst(".NewsBlog-date[data-props]");
            String title = card.select(".NewsBlog-title").text();
            String description = card.select(".NewsBlog-desc").text();
            if (link == null || date == null || title.isEmpty()) continue;
            try {
                URI article = resolveArticle(address, link.attr("href"));
                if (article == null) continue;
                String id = articleId(article);
                String stamp = Instant.parse(new JSONObject(date.attr("data-props")).getString("iso8601")).toString();
                valid++;
                if (!FOREVER.matcher(title + " " + description).find()) continue;
                boolean russian = CYRILLIC.matcher(title).find()
                    && (description.isEmpty() || CYRILLIC.matcher(description).find());
                JSONObject item = new JSONObject().put("id", id).put("version", id + "@" + stamp)
                    .put("publishedAt", stamp).put("url", article.toASCIIString())
                    .put("title", abbreviate(title, 180)).put("summary", abbreviate(description, 270));
                if (russian) item.put("titleRu", item.getString("title"))
                    .put("summaryRu", item.getString("summary")).put("translation", "official");
                items.put(item);
            } catch (Exception malformed) {
                // An incomplete card must not suppress the other published articles.
            }
        }
        if (valid == 0) throw new IOException("No recognizable news cards");
        return items;
    }

    private static String abbreviate(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = text.lastIndexOf(' ', limit - 1);
        return text.substring(0, end > limit / 2 ? end : limit - 1) + "…";
    }

    private static URI resolveArticle(String base, String link) {
        URI article = URI.create(base).resolve(link);
        String path = article.getPath();
        if (path == null || articleId(article) == null) return null;
        if ("worldofwarcraft.blizzard.com".equals(article.getHost()) && path.startsWith("/news/")) {
            String locale = base.equals(RU) ? "ru-ru" : "en-gb";
            return URI.create("https://worldofwarcraft.blizzard.com/" + locale + article.getRawPath());
        }
        return article;
    }

    static String articleId(URI article) {
        if (!"https".equals(article.getScheme()) || article.getUserInfo() != null
                || (article.getPort() != -1 && article.getPort() != 443) || article.getPath() == null) return null;
        String host = article.getHost();
        Pattern path;
        if ("worldofwarcraft.blizzard.com".equals(host)) path = WOW_PATH;
        else if ("news.blizzard.com".equals(host)) path = BLIZZARD_PATH;
        else return null;
        Matcher match = path.matcher(article.getPath());
        return match.matches() ? match.group(1) : null;
    }

    static JSONArray merge(JSONArray... feeds) {
        Map<String, JSONObject> latest = new HashMap<>();
        for (JSONArray feed : feeds) {
            for (int i = 0; i < feed.length(); i++) {
                JSONObject item = feed.optJSONObject(i);
                if (item == null || item.optString("id").isEmpty() || item.optString("version").isEmpty()) continue;
                JSONObject old = latest.get(item.optString("id"));
                try {
                    Instant date = Instant.parse(item.getString("publishedAt"));
                    if (old == null || date.isAfter(Instant.parse(old.getString("publishedAt")))
                            || (item.optString("version").equals(old.optString("version"))
                                && translationRank(item) > translationRank(old))) latest.put(item.getString("id"), item);
                } catch (Exception malformed) {
                    // Ignore a broken cache entry, even when another item is valid.
                }
            }
        }
        ArrayList<JSONObject> ordered = new ArrayList<>(latest.values());
        ordered.sort((a, b) -> {
            int byDate = Instant.parse(b.optString("publishedAt")).compareTo(Instant.parse(a.optString("publishedAt")));
            return byDate == 0 ? b.optString("id").compareTo(a.optString("id")) : byDate;
        });
        JSONArray result = new JSONArray();
        for (int i = 0; i < Math.min(ordered.size(), LIMIT); i++) result.put(ordered.get(i));
        return result;
    }

    private static int translationRank(JSONObject item) {
        String method = item.optString("translation").toLowerCase(Locale.ROOT);
        if (method.equals("official")) return 3;
        if (method.equals("editorial")) return 2;
        return item.optString("titleRu").isEmpty() ? 0 : 1;
    }
}
