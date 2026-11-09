package ru.ivan.azerothpulse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Combines public build lists with the current rules for a single class. */
final class TalentCatalog {
    private static final String ARMORY = "https://armorywowforever.com/api/forever/talents/";
    private static final String MOBALYTICS = "https://mobalytics.gg/wow-forever/classes/";
    private static final String MMONSTER = "https://mmonster.co/wow-forever/tools/talent-calculator/";
    private static final String METAMMO = "https://metammo.com/wow-forever/talents/";
    private static final String[] GOALS = {"leveling", "dungeons", "raid", "pvp"};
    private static final String[] SOURCES = {"mob", "mm", "meta"};

    interface Download { String get(String address) throws Exception; }

    private TalentCatalog() {}

    static JSONObject refresh(String name, JSONObject seed, String seedVersion, JSONObject previous,
            Download download) throws Exception {
        if (!TalentStats.isClass(name)) throw new IOException("Unknown class");
        String slug = name.toLowerCase(Locale.ROOT);
        JSONObject raw = new JSONObject(download.get(ARMORY + slug));
        String version = raw.getString("version");
        if (!"wowf".equals(raw.getString("product")) || raw.getInt("points") != 51 || raw.getInt("maxLevel") != 60
                || raw.getInt("firstLevel") != 10 || raw.getInt("pointsPerRow") != 5
                || !version.matches("[0-9]{1,8}(\\.[0-9]{1,8}){3}")
                || compareVersion(version, previous.optString("version", seedVersion)) < 0) {
            throw new IOException("Unsupported or older talent tree");
        }

        Tree tree = new Tree(name, raw, seed);
        JSONArray curated = seed.getJSONArray("builds");
        JSONArray oldBuilds = previous.getJSONArray("builds");
        List<JSONObject> accepted = new ArrayList<>();
        Set<String> refreshed = new HashSet<>();

        for (String source : SOURCES) {
            List<JSONObject> candidates;
            try {
                switch (source) {
                    case "mob": candidates = TalentGuides.leveling(download.get(MOBALYTICS + slug + "-leveling-guide")); break;
                    case "mm": candidates = TalentGuides.monster(download.get(MMONSTER + slug)); break;
                    default: candidates = TalentGuides.meta(download.get(METAMMO + slug)); break;
                }
            } catch (Exception unavailable) {
                continue;
            }
            refreshed.add(source);
            Set<String> paths = new HashSet<>();
            for (JSONObject candidate : candidates) {
                JSONArray order = candidate.optJSONArray("order");
                JSONObject ranks = candidate.optJSONObject("ranks");
                if (order != null) ranks = tree.countOrder(order);
                JSONArray split = tree.split(ranks);
                if (split == null) continue;
                JSONObject spec = tree.dominant(split);
                String sourceKey = order == null ? candidate.optString("sourceKey") : spec.getString("en");
                if (sourceKey.isEmpty() || sourceKey.length() > 110 || (order != null && !paths.add(sourceKey))) continue;
                boolean curatedGoal = false;
                for (int i = 0; i < curated.length(); i++) {
                    JSONObject preset = curated.getJSONObject(i);
                    if (!source.equals(preset.optString("source")) || !sourceKey.equals(preset.optString("sourceKey"))) continue;
                    accepted.add(build(candidate, preset, spec, source, sourceKey, preset.getString("goal"), split));
                    curatedGoal = true;
                }
                if (!curatedGoal && isGoal(candidate.optString("goal"))) {
                    accepted.add(build(candidate, null, spec, source, sourceKey, candidate.getString("goal"), split));
                }
            }
        }

        if (refreshed.isEmpty() && version.equals(previous.optString("version", seedVersion))) {
            throw new IOException("No current guides available");
        }
        Map<String, JSONObject> merged = new LinkedHashMap<>();
        for (int i = 0; i < curated.length(); i++) {
            JSONObject preset = curated.getJSONObject(i);
            for (JSONObject candidate : accepted) {
                if (key(preset).equals(key(candidate))) addUnique(merged, candidate);
            }
        }
        for (JSONObject candidate : accepted) addUnique(merged, candidate);

        boolean partial = refreshed.size() != SOURCES.length;
        for (int i = 0; i < oldBuilds.length(); i++) {
            JSONObject old = oldBuilds.getJSONObject(i);
            if (!refreshed.contains(old.optString("source"))) preserve(tree, merged, old);
        }
        for (String goal : GOALS) {
            boolean covered = false;
            for (JSONObject entry : merged.values()) covered |= goal.equals(entry.getString("goal"));
            if (covered) continue;
            partial = true;
            for (int i = 0; i < oldBuilds.length(); i++) {
                JSONObject old = oldBuilds.getJSONObject(i);
                if (goal.equals(old.getString("goal"))) preserve(tree, merged, old);
            }
        }
        return new JSONObject().put("version", version).put("checked", Instant.now().toString())
            .put("treeChecked", Instant.now().toString())
            .put("partial", partial).put("trees", tree.trees).put("builds", new JSONArray(merged.values()));
    }

    private static boolean isGoal(String value) {
        for (String goal : GOALS) if (goal.equals(value)) return true;
        return false;
    }

    private static int compareVersion(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < a.length; i++) {
            int comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static JSONObject build(JSONObject raw, JSONObject curated, JSONObject spec, String source,
            String sourceKey, String goal, JSONArray split) throws Exception {
        boolean leveling = raw.has("order");
        String original = raw.optString("title");
        String title = curated != null ? curated.getString("title")
            : leveling ? "Прокачка · " + spec.getString("label")
            : spec.getString("label") + " · " + original.substring(0, Math.min(85, original.length()));
        String role = curated != null ? curated.getString("role")
            : leveling ? "Соло-прокачка" : displayRole(raw.optString("role"));
        JSONObject entry = new JSONObject().put("goal", goal).put("title", title).put("role", role)
            .put("checked", Instant.now().toString())
            .put("source", source).put("sourceKey", sourceKey).put("split", split);
        String field = leveling ? "order" : "ranks";
        entry.put(field, raw.get(field));
        if (curated != null && signature(curated).equals(signature(entry))) entry.put("note", curated.optString("note"));
        return entry;
    }

    private static String displayRole(String role) {
        switch (role) {
            case "Tank": return "Танк";
            case "Healer": return "Лекарь";
            case "Raid DPS": return "Урон";
            case "PvP": return "PvP";
            default: return "Готовый билд";
        }
    }

    private static String key(JSONObject build) {
        return build.optString("source") + "|" + build.optString("sourceKey") + "|" + build.optString("goal");
    }

    private static String signature(JSONObject build) {
        JSONArray order = build.optJSONArray("order");
        if (order != null) return build.optString("goal") + order;
        JSONObject ranks = build.optJSONObject("ranks");
        if (ranks == null) return "";
        List<String> ids = new ArrayList<>();
        ranks.keys().forEachRemaining(ids::add);
        Collections.sort(ids);
        StringBuilder result = new StringBuilder(build.optString("goal"));
        for (String id : ids) result.append('|').append(id).append(':').append(ranks.optInt(id));
        return result.toString();
    }

    private static void addUnique(Map<String, JSONObject> builds, JSONObject value) {
        if (builds.size() < 100) builds.putIfAbsent(signature(value), value);
    }

    private static void preserve(Tree tree, Map<String, JSONObject> builds, JSONObject entry) throws Exception {
        JSONArray order = entry.optJSONArray("order");
        JSONArray split = tree.split(order == null ? entry.optJSONObject("ranks") : tree.countOrder(order));
        if (split != null) {
            JSONObject copy = new JSONObject(entry.toString()).put("split", split).put("cached", true);
            addUnique(builds, copy);
        }
    }

    static JSONArray effects(JSONObject talent, JSONObject known, int max) throws Exception {
        JSONArray source = talent.optJSONArray("desc");
        if (source == null) return null;
        JSONArray localized = known == null ? null : known.optJSONArray("effects");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        JSONArray result = new JSONArray();
        boolean hasDescription = false;
        for (int i = 0; i < max && i < source.length(); i++) {
            String text = source.optString(i, "").replace("\r\n", "\n").trim();
            if (source.isNull(i) || text.isEmpty() || text.length() > 1800) {
                result.put(JSONObject.NULL);
                continue;
            }
            hasDescription = true;
            JSONObject translation = localized == null ? null : localized.optJSONObject(i);
            StringBuilder fingerprint = new StringBuilder(64);
            for (byte value : digest.digest(text.getBytes(StandardCharsets.UTF_8))) {
                fingerprint.append(Character.forDigit((value & 0xff) >>> 4, 16));
                fingerprint.append(Character.forDigit(value & 0x0f, 16));
            }
            if (translation != null && fingerprint.toString().equals(translation.optString("fingerprint"))) {
                result.put(new JSONObject().put("text", translation.getString("text")).put("lang", translation.optString("lang", "ru")).put("fingerprint", fingerprint.toString()));
            } else {
                result.put(new JSONObject().put("text", text).put("lang", "en").put("fingerprint", fingerprint.toString()));
            }
        }
        return hasDescription ? result : null;
    }

    private static final class Tree {
        final JSONArray trees = new JSONArray();
        final Map<Integer, Node> nodes = new LinkedHashMap<>();
        final List<Node> ordered = new ArrayList<>();

        Tree(String name, JSONObject raw, JSONObject seed) throws Exception {
            JSONObject playerClass = raw.getJSONObject("class");
            JSONArray specs = playerClass.getJSONArray("specs");
            if (!name.equals(playerClass.getString("name")) || specs.length() != 3) throw new IOException("Wrong class tree");
            JSONArray existingTrees = seed.getJSONArray("trees");
            Map<String, JSONObject> existingSpecs = new HashMap<>();
            Map<Integer, JSONObject> existingNodes = new HashMap<>();
            for (int i = 0; i < existingTrees.length(); i++) {
                JSONObject spec = existingTrees.getJSONObject(i);
                existingSpecs.put(spec.getString("en"), spec);
                JSONArray old = spec.getJSONArray("nodes");
                for (int j = 0; j < old.length(); j++) existingNodes.put(old.getJSONObject(j).getInt("id"), old.getJSONObject(j));
            }
            for (int i = 0; i < 3; i++) {
                JSONObject spec = specs.getJSONObject(i);
                String en = spec.getString("name");
                JSONObject knownSpec = existingSpecs.get(en);
                JSONArray incoming = spec.getJSONArray("talents");
                if (en.isEmpty() || en.length() > 60 || incoming.length() < 8 || incoming.length() > 40) {
                    throw new IOException("Unexpected talent branch");
                }
                JSONArray output = new JSONArray();
                Set<Integer> cells = new HashSet<>();
                for (int j = 0; j < incoming.length(); j++) {
                    JSONObject talent = incoming.getJSONObject(j);
                    Node node = new Node(i, talent);
                    if (!cells.add(node.row * 4 + node.col) || nodes.put(node.id, node) != null) throw new IOException("Duplicate talent");
                    JSONObject known = existingNodes.get(node.id);
                    String english = talent.getString("name");
                    if (english.isEmpty() || english.length() > 100) throw new IOException("Unexpected talent name");
                    JSONObject item = new JSONObject().put("id", node.id).put("name", english).put("en", english)
                        .put("row", node.row).put("col", node.col).put("max", node.max);
                    if (known != null && english.equals(known.optString("en"))) {
                        item.put("name", known.getString("name")).put("icon", known.opt("icon"));
                    }
                    item.put("effects", effects(talent, known, node.max));
                    if (node.req != 0) item.put("req", node.req);
                    output.put(item);
                }
                trees.put(new JSONObject().put("label", knownSpec == null ? en : knownSpec.getString("label"))
                    .put("en", en).put("nodes", output));
            }
            for (Node node : nodes.values()) {
                if (node.req != 0 && (!nodes.containsKey(node.req) || node.req == node.id)) throw new IOException("Missing prerequisite");
                ordered.add(node);
            }
            ordered.sort(Comparator.comparingInt(node -> node.row));
        }

        JSONObject dominant(JSONArray split) throws Exception {
            int best = 0;
            for (int i = 1; i < 3; i++) if (split.getInt(i) > split.getInt(best)) best = i;
            return trees.getJSONObject(best);
        }

        JSONObject countOrder(JSONArray order) throws Exception {
            if (order.length() != 51) return null;
            Map<Integer, Integer> spent = new HashMap<>();
            for (int i = 0; i < order.length(); i++) {
                Node node = nodes.get(order.getInt(i));
                if (!canSpend(node, spent)) return null;
                spent.put(node.id, spent.getOrDefault(node.id, 0) + 1);
            }
            Map<String, Integer> result = new HashMap<>();
            for (Map.Entry<Integer, Integer> entry : spent.entrySet()) result.put(entry.getKey().toString(), entry.getValue());
            return new JSONObject(result);
        }

        JSONArray split(JSONObject ranks) throws Exception {
            if (ranks == null) return null;
            Map<Integer, Integer> targets = new HashMap<>();
            int total = 0;
            java.util.Iterator<String> keys = ranks.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                Node node = nodes.get(Integer.parseInt(id));
                int rank = ranks.getInt(id);
                if (node == null || rank <= 0 || rank > node.max) return null;
                total += rank;
                targets.put(node.id, rank);
            }
            if (total != 51) return null;
            Map<Integer, Integer> spent = new HashMap<>();
            int[] split = new int[3];
            while (total > 0) {
                int before = total;
                for (Node node : ordered) {
                    int target = targets.getOrDefault(node.id, 0);
                    if (target > spent.getOrDefault(node.id, 0) && canSpend(node, spent)) {
                        spent.put(node.id, target);
                        split[node.branch] += target;
                        total -= target;
                    }
                }
                if (total == before) return null;
            }
            return new JSONArray().put(split[0]).put(split[1]).put(split[2]);
        }

        private boolean canSpend(Node node, Map<Integer, Integer> spent) {
            if (node == null || spent.getOrDefault(node.id, 0) >= node.max
                    || (node.req != 0 && spent.getOrDefault(node.req, 0) < nodes.get(node.req).max)) return false;
            int prior = 0;
            for (Map.Entry<Integer, Integer> entry : spent.entrySet()) {
                Node used = nodes.get(entry.getKey());
                if (used.branch == node.branch && used.row < node.row) prior += entry.getValue();
            }
            return prior >= node.row * 5;
        }
    }

    private static final class Node {
        final int id, branch, row, col, max, req;

        Node(int branch, JSONObject data) throws Exception {
            this.branch = branch;
            id = data.getInt("id");
            row = data.getInt("row");
            col = data.getInt("col");
            max = data.getInt("maxRanks");
            req = data.optInt("prereq");
            if (id <= 0 || row < 0 || row > 10 || col < 0 || col > 3 || max < 1 || max > 9) {
                throw new IOException("Unsupported talent node");
            }
        }
    }
}
