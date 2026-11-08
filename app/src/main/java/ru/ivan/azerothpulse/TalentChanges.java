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
        try { return new JSONArray(preferences.getString(JOURNAL, "[]")); }
        catch (JSONException invalid) { return new JSONArray(); }
    }

    JSONObject state() throws JSONException {
        JSONArray entries = journal();
        long seen = preferences.getLong(SEEN, 0);
        int unread = 0;
        for (int i = 0; i < entries.length(); i++) {
            if (entries.getJSONObject(i).getLong("id") > seen) unread++;
        }
        return new JSONObject().put("entries", entries).put("unread", unread)
            .put("latest", preferences.getLong(SEQUENCE, 0));
    }

    void viewed(long latest) {
        long sequence = preferences.getLong(SEQUENCE, 0);
        if (latest <= preferences.getLong(SEEN, 0) || latest > sequence) return;
        preferences.edit().putLong(SEEN, latest).apply();
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
        String oldVersion = before.optString("version");
        String version = after.optString("version");
        if (!oldVersion.isEmpty() && !oldVersion.equals(version)) {
            changes.put("Обновлено дерево талантов " + oldVersion + " → " + version + ".");
        }
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
        ArrayList<String> changedNodes = new ArrayList<>();
        for (Map.Entry<String, JSONObject> entry : newNodes.entrySet()) {
            JSONObject old = oldNodes.get(entry.getKey());
            if (old == null || !canonical(nodeContent(old)).equals(canonical(nodeContent(entry.getValue())))) {
                changedNodes.add(entry.getValue().optString("name"));
            }
        }
        for (Map.Entry<String, JSONObject> entry : oldNodes.entrySet()) {
            if (!newNodes.containsKey(entry.getKey())) changedNodes.add(entry.getValue().optString("name"));
        }
        if (!changedNodes.isEmpty()) changes.put("Изменены таланты или их описания. " + String.join(", ", changedNodes) + ".");
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

    private static JSONObject nodeContent(JSONObject node) throws JSONException {
        JSONObject result = new JSONObject();
        for (String key : new String[]{"id", "en", "row", "col", "max", "req", "_tree"}) result.put(key, node.opt(key));
        JSONArray effects = node.optJSONArray("effects");
        if (effects != null) {
            JSONArray content = new JSONArray();
            for (int i = 0; i < effects.length(); i++) {
                JSONObject effect = effects.getJSONObject(i);
                content.put(effect.optString("fingerprint", effect.optString("text")));
            }
            result.put("effects", content);
        }
        return result;
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
