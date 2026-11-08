package ru.ivan.azerothpulse;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/** Extracts plain text for the local reader. Website markup never enters the WebView. */
final class BlizzardArticle {
    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");
    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final int MAX_TEXT = 100_000;

    private BlizzardArticle() {}

    static JSONObject fetch(String address, String id) throws Exception {
        URI original = URI.create(address);
        if (!id.equals(BlizzardNews.articleId(original))) throw new IOException("Unknown article");
        String localizedPath = original.getRawPath().replaceFirst("^/[a-z]{2}-[a-z]{2}/", "/");
        String russian = "https://" + original.getHost() + "/ru-ru" + localizedPath;
        if (id.equals(BlizzardNews.articleId(URI.create(russian)))) {
            try {
                JSONObject localized = parse(ForeverSources.downloadArticle(russian, id));
                if ("official".equals(localized.getString("translation"))) return localized;
            } catch (Exception unavailable) {
                // Blizzard does not provide a Russian edition for every publication.
            }
        }
        return fetchOriginal(address, id);
    }

    static JSONObject fetchOriginal(String address, String id) throws Exception {
        URI source = URI.create(address);
        if (!id.equals(BlizzardNews.articleId(source))) throw new IOException("Unknown article");
        String path = source.getRawPath();
        if (!path.startsWith("/en-")) {
            String locale = "news.blizzard.com".equals(source.getHost()) ? "en-us" : "en-gb";
            path = "/" + locale + path.replaceFirst("^/[a-z]{2}-[a-z]{2}/", "/");
            address = "https://" + source.getHost() + path;
        }
        JSONObject article = parse(ForeverSources.downloadArticle(address, id));
        if (!"original".equals(article.optString("translation"))) throw new IOException("Original unavailable");
        return article;
    }

    static JSONObject parse(String html) throws Exception {
        Element body = Jsoup.parse(html).selectFirst(
            "#blog .Blog .detail, article [itemprop=articleBody], .ArticleDetail-content");
        if (body == null) throw new IOException("Article text missing");
        body.select("script,style,noscript,iframe,video,object,embed,svg,button,nav,aside").remove();
        ArticleText text = new ArticleText();
        text.read(body, "p", 0);
        text.flush("p", 0);
        if (text.blocks.length() == 0) throw new IOException("Article text missing");
        int withoutCyrillic = CYRILLIC.matcher(text.languageSample).replaceAll("").length();
        int withoutLatin = LATIN.matcher(text.languageSample).replaceAll("").length();
        return new JSONObject().put("blocks", text.blocks)
            .put("translation", withoutCyrillic < withoutLatin ? "official" : "original");
    }

    private static final class ArticleText {
        final JSONArray blocks = new JSONArray();
        final StringBuilder line = new StringBuilder();
        final StringBuilder languageSample = new StringBuilder();
        int length;

        void read(Node node, String type, int depth) throws Exception {
            if (node instanceof TextNode) {
                line.append(((TextNode) node).getWholeText());
                return;
            }
            if (!(node instanceof Element)) return;
            Element element = (Element) node;
            String tag = element.normalName();
            if ("br".equals(tag)) {
                flush(type, depth);
                return;
            }
            if ("tr".equals(tag) && element.childrenSize() > 1) {
                flush(type, depth);
                line.append(String.join("  |  ", element.children().eachText()));
                flush("row", 0);
                return;
            }
            String block = blockType(tag, type);
            boolean list = "ol".equals(tag) || "ul".equals(tag);
            if (block != null || list) flush(type, depth);
            String childType = block == null ? type : block;
            int childDepth = list ? depth + 1 : depth;
            for (Node child : element.childNodes()) read(child, childType, childDepth);
            if (block != null || list) flush(childType, childDepth);
        }

        void flush(String type, int depth) throws Exception {
            String value = SPACE.matcher(line).replaceAll(" ").trim();
            line.setLength(0);
            if (value.isEmpty()) return;
            length += value.length();
            if (length > MAX_TEXT) throw new IOException("Article text too large");
            if (languageSample.length() < 2_000) languageSample.append(value).append(' ');
            blocks.put(new JSONObject().put("type", type).put("text", value)
                .put("depth", Math.max(0, Math.min(3, depth - 1))));
        }

        private static String blockType(String tag, String parent) {
            switch (tag) {
                case "h1": case "h2": case "h3": case "h4": case "h5": case "h6": return "heading";
                case "li": return "bullet";
                case "blockquote": return "quote";
                case "p": case "div": case "section": case "table": case "td": case "figcaption": return parent;
                default: return null;
            }
        }
    }
}
