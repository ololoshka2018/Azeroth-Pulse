package ru.ivan.azerothpulse;

import android.content.SharedPreferences;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class TalentChanges {
    private static final String JOURNAL = "talentChangesV1";
    private static final String SEQUENCE = "talentChangesSequence";
    private static final String SEEN = "talentChangesSeen";
    private static final int LIMIT = 30;
    private final SharedPreferences preferences;

    TalentChanges(SharedPreferences preferences) { this.preferences = preferences; }

    private JSONArray journal() {
        JSONArray clean = new JSONArray();
        try {
            JSONArray stored = new JSONArray(preferences.getString(JOURNAL, "[]"));
            for (int i = 0; i < stored.length(); i++) {
                JSONObject entry = stored.getJSONObject(i);
                JSONArray items = entry.optJSONArray("items");
                if (items == null) continue;
                JSONArray retained = new JSONArray();
                for (int j = 0; j < items.length(); j++) {
                    String item = items.optString(j, "");
                    if (!item.isEmpty() && !item.startsWith("Изменены таланты или их описания. ")
                            && !item.startsWith("Обновлено дерево талантов ")) retained.put(item);
                }
                if (retained.length() > 0) clean.put(entry.put("items", retained));
            }
        } catch (JSONException invalid) { return new JSONArray(); }
        return clean;
    }

    JSONObject state() throws JSONException {
        JSONArray entries = journal();
        long seen = preferences.getLong(SEEN, 0);
        java.util.Set<String> read = preferences.getStringSet("talentReadEntriesV2", java.util.Collections.emptySet());
        int unread = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            boolean fresh = entry.getLong("id") > seen && !read.contains(String.valueOf(entry.getLong("id")));
            entry.put("unread", fresh);
            if (fresh) unread++;
        }
        return new JSONObject().put("entries", entries).put("unread", unread)
            .put("latest", preferences.getLong(SEQUENCE, 0));
    }

    void viewed(long latest) {
        long sequence = preferences.getLong(SEQUENCE, 0);
        if (latest <= preferences.getLong(SEEN, 0) || latest > sequence) return;
        java.util.Set<String> read = new java.util.HashSet<>(preferences.getStringSet("talentReadEntriesV2", java.util.Collections.emptySet()));
        read.add(String.valueOf(latest));
        JSONArray active = journal();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < active.length(); i++) ids.add(String.valueOf(active.optJSONObject(i).optLong("id")));
        read.retainAll(ids);
        preferences.edit().putStringSet("talentReadEntriesV2", read).apply();
    }

    void saveCatalog(String className, JSONObject before, JSONObject after) throws JSONException {
        JSONArray changes = diff(before, after);
        SharedPreferences.Editor edit = preferences.edit().putString(className, after.toString());
        if (changes.length() == 0) {
            edit.apply();
            return;
        }
        long sequence = preferences.getLong(SEQUENCE, 0) + 1;
        JSONArray entries = new JSONArray().put(new JSONObject().put("id", sequence).put("className", className)
            .put("at", Instant.now().toString()).put("items", changes));
        JSONArray previous = journal();
        for (int i = 0; i < previous.length() && entries.length() < LIMIT; i++) entries.put(previous.getJSONObject(i));
        edit.putLong(SEQUENCE, sequence).putString(JOURNAL, entries.toString()).apply();
    }

    static JSONArray diff(JSONObject before, JSONObject after) throws JSONException {
        JSONArray changes = new JSONArray();
        Map<String, JSONObject> oldBuilds = builds(before);
        Map<String, JSONObject> newBuilds = builds(after);
        Map<String, JSONObject> oldNodes = nodes(before);
        Map<String, JSONObject> newNodes = nodes(after);
        for (Map.Entry<String, JSONObject> entry : newBuilds.entrySet()) {
            JSONObject next = entry.getValue();
            JSONObject old = oldBuilds.get(entry.getKey());
            String title = "«" + next.optString("title", "Билд") + "»";
            if (old == null) {
                changes.put("Добавлен билд " + title + ".");
            } else if (!canonical(allocation(old)).equals(canonical(allocation(next)))) {
                ArrayList<String> ranks = new ArrayList<>();
                JSONObject oldRanks = allocation(old);
                JSONObject newRanks = allocation(next);
                TreeSet<String> ids = keys(oldRanks);
                ids.addAll(keys(newRanks));
                for (String id : ids) {
                    int a = oldRanks.optInt(id);
                    int b = newRanks.optInt(id);
                    if (a == b) continue;
                    JSONObject node = newNodes.containsKey(id) ? newNodes.get(id) : oldNodes.get(id);
                    String label = node == null ? "Талант " + id : node.optString("name");
                    ranks.add(label + " " + a + " → " + b);
                }
                changes.put(title + " — изменены очки. " + String.join(", ", ranks) + ".");
            } else if (!canonical(old.optJSONArray("order")).equals(canonical(next.optJSONArray("order")))) {
                changes.put(title + " — изменён порядок распределения очков.");
            } else if (!old.optString("title").equals(next.optString("title"))
                    || !old.optString("role").equals(next.optString("role"))
                    || !old.optString("note").equals(next.optString("note"))) {
                changes.put(title + " — обновлены название или рекомендации.");
            }
        }
        if (!after.optBoolean("partial")) {
            for (Map.Entry<String, JSONObject> entry : oldBuilds.entrySet()) {
                if (!newBuilds.containsKey(entry.getKey())) changes.put("Удалён билд «" + entry.getValue().optString("title") + "».");
            }
        }
        for (Map.Entry<String, JSONObject> entry : newNodes.entrySet()) {
            JSONObject old = oldNodes.get(entry.getKey());
            JSONObject next = entry.getValue();
            String label = "«" + next.optString("name") + "»";
            if (old == null) {
                changes.put("Добавлен талант " + label + ".");
                continue;
            }
            ArrayList<String> details = new ArrayList<>();
            if (!old.optString("en").equals(next.optString("en"))) details.add("прежнее название «" + old.optString("name") + "»");
            if (old.optInt("max") != next.optInt("max")) details.add("максимум очков " + old.optInt("max") + " → " + next.optInt("max"));
            if (!old.optString("_tree").equals(next.optString("_tree"))
                    || old.optInt("row") != next.optInt("row") || old.optInt("col") != next.optInt("col")) {
                details.add("новая позиция в дереве");
            }
            if (old.optInt("req") != next.optInt("req")) details.add("изменено требование к предыдущему таланту");
            ArrayList<String> ranks = changedDescriptions(old.optJSONArray("effects"), next.optJSONArray("effects"));
            if (!ranks.isEmpty()) details.add("изменение описания (ранги " + String.join(", ", ranks) + ")");
            if (!details.isEmpty()) {
                changes.put(label + " — " + String.join(", ", details) + ".");
            }
        }
        for (Map.Entry<String, JSONObject> entry : oldNodes.entrySet()) {
            if (!newNodes.containsKey(entry.getKey())) changes.put("Удалён талант «" + entry.getValue().optString("name") + "».");
        }
        return changes;
    }

    private static Map<String, JSONObject> builds(JSONObject catalog) throws JSONException {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        JSONArray builds = catalog.getJSONArray("builds");
        for (int i = 0; i < builds.length(); i++) {
            JSONObject build = builds.getJSONObject(i);
            String id = build.optString("source") + "|" + build.optString("sourceKey") + "|" + build.optString("goal");
            result.put(id, build);
        }
        return result;
    }

    private static Map<String, JSONObject> nodes(JSONObject catalog) throws JSONException {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        JSONArray trees = catalog.getJSONArray("trees");
        for (int i = 0; i < trees.length(); i++) {
            JSONObject tree = trees.getJSONObject(i);
            JSONArray nodes = tree.getJSONArray("nodes");
            for (int j = 0; j < nodes.length(); j++) {
                JSONObject node = nodes.getJSONObject(j);
                result.put(String.valueOf(node.getInt("id")), new JSONObject(node.toString()).put("_tree", tree.optString("en")));
            }
        }
        return result;
    }

    private static JSONObject allocation(JSONObject build) throws JSONException {
        if (build.has("ranks")) return build.getJSONObject("ranks");
        JSONObject counts = new JSONObject();
        JSONArray order = build.getJSONArray("order");
        for (int i = 0; i < order.length(); i++) {
            String id = String.valueOf(order.getInt(i));
            counts.put(id, counts.optInt(id) + 1);
        }
        return counts;
    }

    private static ArrayList<String> changedDescriptions(JSONArray before, JSONArray after) {
        ArrayList<String> ranks = new ArrayList<>();
        if (before == null || after == null) return ranks;
        for (int i = 0; i < Math.min(before.length(), after.length()); i++) {
            JSONObject old = before.optJSONObject(i), next = after.optJSONObject(i);
            if (old == null || next == null) continue;
            String a = old.optString("fingerprint"), b = next.optString("fingerprint");
            if (a.isEmpty() || b.isEmpty() || a.equals(b)) continue;
            if ("en".equals(old.optString("lang")) && "en".equals(next.optString("lang"))
                    && old.optString("text").replaceAll("\\s+", " ").trim().equals(next.optString("text").replaceAll("\\s+", " ").trim())) continue;
            ranks.add(String.valueOf(i + 1));
        }
        return ranks;
    }

    private static TreeSet<String> keys(JSONObject object) {
        TreeSet<String> result = new TreeSet<>();
        for (Iterator<String> keys = object.keys(); keys.hasNext();) result.add(keys.next());
        return result;
    }

    private static String canonical(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> fields = new ArrayList<>();
            for (String key : keys(object)) fields.add(JSONObject.quote(key) + ":" + canonical(object.get(key)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            ArrayList<String> items = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) items.add(canonical(array.get(i)));
            return "[" + String.join(",", items) + "]";
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }
}
