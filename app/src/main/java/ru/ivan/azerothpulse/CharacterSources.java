package ru.ivan.azerothpulse;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Reads public Blizzard Armory JSON; website JavaScript is never executed. */
final class CharacterSources {
    private static final Pattern MODEL = Pattern.compile("(?:^|[;\\n])\\s*(?:var\\s+)?model\\s*=\\s*");
    static String region(String value) throws IOException {
        if (!"EU".equals(value) && !"US".equals(value)) throw new IOException("Unknown region");
        return value.toLowerCase(Locale.ROOT);
    }
    static String slug(String value) throws IOException {
        String slug = Normalizer.normalize(value.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
        if (!slug.matches("[\\p{L}]{1,32}-[\\p{L}]{1,32}")) throw new IOException("Use a first name and surname");
        return slug;
    }
    static String profileUrl(String region, String slug) throws Exception {
        return "https://worldofwarcraft.blizzard.com/en-us/forever/" + region(region) + "/armory/character/"
            + URLEncoder.encode(slug(slug), "UTF-8").replace("+", "%20") + "/";
    }
    static JSONObject model(String html) throws Exception {
        for (Element script : Jsoup.parse(html).select("script:not([src])")) {
            Matcher matcher = MODEL.matcher(script.data());
            if (matcher.find()) {
                Object value = new JSONTokener(script.data().substring(matcher.end())).nextValue();
                if (value instanceof JSONObject) return (JSONObject) value;
            }
        }
        throw new IOException("Armory format unavailable");
    }
    static JSONArray search(String html, String region) throws Exception {
        JSONObject model = model(html);
        JSONArray source = model.optJSONArray("results");
        if (source == null) throw new IOException("Search format unavailable");
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length() && result.length() < 20; i++) {
            try { result.put(identity(source.getJSONObject(i), region)); }
            catch (Exception invalid) { /* One malformed result must not suppress others. */ }
        }
        return result;
    }
    static JSONObject profile(String html, String region, String expected) throws Exception {
        JSONObject model = model(html);
        JSONArray mounts = model.optJSONArray("reactMounts");
        if (mounts == null) throw new IOException("Character unavailable");
        for (int i = 0; i < mounts.length(); i++) {
            JSONObject mount = mounts.getJSONObject(i);
            if (!"character-profile-mount".equals(mount.optString("id"))) continue;
            JSONObject raw = mount.getJSONObject("initialState").getJSONObject("character");
            JSONObject result = identity(raw, region);
            if (!slug(expected).equals(result.getString("slug"))) throw new IOException("Wrong character");
            result.put("checkedAt", Instant.now().toString());
            result.put("itemLevel", raw.optInt("averageItemLevel", 0));
            JSONObject media = raw.optJSONObject("media");
            String render = media == null ? "" : image(media.optJSONObject("main-raw"));
            if (render.isEmpty() && media != null) render = image(media.optJSONObject("main-sd-raw"));
            result.put("render", render).put("visualsVersion", 2);
            JSONArray gear = new JSONArray();
            JSONObject rawGear = raw.optJSONObject("gear");
            if (rawGear != null) {
                java.util.Iterator<String> keys = rawGear.keys();
                while (keys.hasNext() && gear.length() < 25) {
                    JSONObject item = rawGear.optJSONObject(keys.next()); if (item == null) continue;
                    JSONObject slot = item.optJSONObject("slot"), level = item.optJSONObject("level"), quality = item.optJSONObject("quality");
                    JSONArray bonuses = new JSONArray(), rawBonuses = item.optJSONArray("stats");
                    if (rawBonuses != null) for (int b = 0; b < rawBonuses.length() && b < 12; b++) {
                        JSONObject bonus = rawBonuses.optJSONObject(b); if (bonus == null) continue;
                        JSONObject type = bonus.optJSONObject("type");
                        bonuses.put(new JSONObject().put("type", type == null ? "" : type.optString("type"))
                            .put("value", bonus.optDouble("value", 0)));
                    }
                    JSONObject entry = new JSONObject().put("id", item.optInt("id")).put("name", text(item.optString("name"), 160))
                        .put("slot", slot == null ? "" : slot.optString("type"))
                        .put("level", level == null ? 0 : level.optInt("value"))
                        .put("quality", quality == null ? "COMMON" : quality.optString("type"))
                        .put("icon", itemIcon(item)).put("stats", bonuses);
                    JSONObject transmog = item.optJSONObject("transmog");
                    JSONObject appearance = transmog == null ? null : transmog.optJSONObject("item");
                    if (appearance != null && appearance.optInt("id") > 0 && !appearance.optString("name").isEmpty()) {
                        entry.put("transmog", new JSONObject().put("id", appearance.getInt("id"))
                            .put("name", text(appearance.getString("name"), 160)));
                    }
                    gear.put(entry);
                }
            }
            result.put("gear", gear);
            JSONArray stats = new JSONArray();
            JSONObject rawStats = raw.optJSONObject("stats");
            JSONArray groups = rawStats == null ? null : rawStats.optJSONArray("groups");
            java.util.Set<String> seen = new java.util.HashSet<>();
            if (groups != null) for (int g = 0; g < groups.length(); g++) {
                addStats(groups.getJSONObject(g).optJSONArray("stats"), stats, seen);
            }
            if (rawStats != null) addStats(rawStats.optJSONArray("overview"), stats, seen);
            result.put("stats", stats);
            JSONArray professions = new JSONArray(); JSONObject jobs = raw.optJSONObject("professions");
            if (jobs != null) for (String category : new String[]{"primaries", "secondaries"}) {
                JSONArray entries = jobs.optJSONArray(category); if (entries == null) continue;
                for (int n = 0; n < entries.length() && professions.length() < 8; n++) {
                    JSONObject job = entries.getJSONObject(n); JSONArray tiers = job.optJSONArray("tiers");
                    JSONObject tier = tiers == null ? null : tiers.optJSONObject(tiers.length() - 1);
                    professions.put(new JSONObject().put("name", text(job.optString("name"), 80))
                        .put("skill", tier == null ? 0 : tier.optInt("skillPoints"))
                        .put("max", tier == null ? 0 : tier.optInt("maxSkillPoints")));
                }
            }
            result.put("professions", professions);
            JSONArray specs = new JSONArray(); JSONArray rawSpecs = raw.optJSONArray("specs");
            if (rawSpecs != null) for (int n = 0; n < rawSpecs.length() && n < 3; n++) {
                JSONObject spec = rawSpecs.getJSONObject(n);
                specs.put(new JSONObject().put("name", text(spec.optString("name"), 80)).put("points", spec.optInt("spentPoints")));
            }
            return result.put("specs", specs);
        }
        throw new IOException("Character unavailable");
    }
    private static void addStats(JSONArray entries, JSONArray stats, java.util.Set<String> seen) throws Exception {
        if (entries == null) return;
        for (int i = 0; i < entries.length() && stats.length() < 40; i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String key = entry.optString("enum");
            JSONObject value = entry.optJSONObject("value");
            if (key.isEmpty() || value == null || seen.contains(key)) continue;
            JSONObject stat = new JSONObject().put("type", key);
            if (value.opt("value") instanceof Number) {
                stat.put("value", value.getDouble("value")).put("percentage", "PERCENTAGE".equals(value.optString("type")));
            } else if ("RANGE".equals(value.optString("type"))) {
                JSONObject min = value.optJSONObject("min"), max = value.optJSONObject("max");
                if (min == null || max == null || !(min.opt("value") instanceof Number) || !(max.opt("value") instanceof Number)) continue;
                stat.put("min", min.getDouble("value")).put("max", max.getDouble("value"));
            } else {
                continue;
            }
            stats.put(stat);
            seen.add(key);
        }
    }
    private static JSONObject identity(JSONObject raw, String region) throws Exception {
        String r = region(region);
        if (!r.equals(raw.optString("region").toLowerCase(Locale.ROOT))) throw new IOException("Wrong region");
        String slug = slug(raw.getString("slug"));
        JSONObject playerClass = raw.getJSONObject("class"), race = raw.getJSONObject("race"), faction = raw.getJSONObject("faction");
        String className = playerClass.getString("name");
        if (!TalentStats.isClass(className)) throw new IOException("Unknown class");
        int level = raw.getInt("level"); if (level < 1 || level > 60) throw new IOException("Invalid level");
        JSONObject ruleset = raw.optJSONObject("ruleset"), guild = raw.optJSONObject("guild"), stamp = raw.optJSONObject("lastUpdatedTimestamp");
        return new JSONObject().put("key", r + ":" + slug).put("slug", slug).put("region", region)
            .put("name", text(raw.getString("name"), 64)).put("surname", text(raw.getString("surname"), 64))
            .put("className", className).put("race", text(race.optString("name"), 80)).put("faction", faction.optString("name"))
            .put("level", level).put("guild", guild == null ? "" : text(guild.optString("name"), 100))
            .put("ruleset", ruleset == null ? "" : ruleset.optString("name"))
            .put("observedAt", stamp == null ? "" : stamp.optString("iso8601"))
            .put("avatar", image(raw.optJSONObject("avatar"))).put("url", profileUrl(region, slug));
    }
    static boolean allowedImage(String address) {
        try {
            URI uri = URI.create(address);
            return "https".equals(uri.getScheme()) && "render.worldofwarcraft.com".equals(uri.getHost())
                && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getQuery() == null && uri.getFragment() == null
                && uri.getPath().matches("/forever-(eu|us)/(character|icons)/[a-zA-Z0-9/_.-]+\\.(jpg|png)");
        } catch (Exception invalid) { return false; }
    }
    private static String itemIcon(JSONObject item) {
        JSONObject media = item.optJSONObject("media");
        JSONObject content = media == null ? null : media.optJSONObject("content");
        JSONArray assets = content == null ? null : content.optJSONArray("assets");
        if (assets == null) return "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && "icon".equals(asset.optString("key"))) {
                String address = asset.optString("value");
                if (allowedImage(address)) return address;
            }
        }
        return "";
    }
    private static String image(JSONObject object) { String value = object == null ? "" : object.optString("url"); return allowedImage(value) ? value : ""; }
    private static String text(String text, int limit) { return text.substring(0, Math.min(limit, text.length())); }
}
