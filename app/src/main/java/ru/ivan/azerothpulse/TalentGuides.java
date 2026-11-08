package ru.ivan.azerothpulse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

final class TalentGuides {
    private static final Pattern LEVELING = Pattern.compile("\"levelingPath\"\\s*:\\s*");

    private TalentGuides() {}

    static List<JSONObject> leveling(String html) throws Exception {
        List<JSONObject> builds = new ArrayList<>();
        for (Element script : Jsoup.parse(html).select("script:not([src])")) {
            String text = script.data();
            if (!text.startsWith("window.__PRELOADED_STATE__=")) continue;
            Matcher matches = LEVELING.matcher(text);
            while (matches.find()) {
                Object raw = new JSONTokener(text.substring(matches.end())).nextValue();
                if (!(raw instanceof JSONArray) || ((JSONArray) raw).length() != 51) continue;
                JSONArray path = (JSONArray) raw;
                JSONArray order = new JSONArray();
                for (int i = 0; i < path.length(); i++) order.put(path.getJSONObject(i).getInt("id"));
                builds.add(new JSONObject().put("goal", "leveling").put("order", order));
            }
        }
        return nonempty(builds);
    }

    static List<JSONObject> monster(String html) throws Exception {
        Document page = Jsoup.parse(html);
        JSONArray trees = null;
        for (Element script : page.select("script:not([src])")) {
            String text = script.data();
            if (!text.startsWith("{\"locale\":") || !text.contains("\"cls\"")) continue;
            trees = new JSONObject(text).getJSONObject("cls").getJSONArray("trees");
            break;
        }
        if (trees == null) throw new IOException("Guide tree missing");
        JSONArray layout = layout(trees, true);
        List<JSONObject> builds = new ArrayList<>();
        for (Element button : page.select("button[data-rb-code][data-rb-name][data-rb-goal]")) {
            String label = button.attr("data-rb-name");
            String goal = button.attr("data-rb-goal");
            if (goal.equals("leveling")) continue;
            if (label.matches("(?i).*\\braid\\b.*")) goal = "raid";
            builds.add(candidate(label, label, goal, "", button.attr("data-rb-code"), layout));
        }
        return nonempty(builds);
    }

    static List<JSONObject> meta(String html) throws Exception {
        for (Element script : Jsoup.parse(html).select("script:not([src])")) {
            String text = script.data();
            if (!text.startsWith("self.__next_f.push([") || !text.contains("\\\"presets\\\"")
                    || !text.contains("\\\"trees\\\"")) continue;
            Object raw = new JSONTokener(text.substring(text.indexOf(',') + 1)).nextValue();
            if (!(raw instanceof String)) continue;
            String decoded = (String) raw;
            JSONArray layout = layout(arrayAfter(decoded, "\"trees\":"), false);
            JSONArray presets = arrayAfter(decoded, "\"presets\":");
            List<JSONObject> builds = new ArrayList<>();
            for (int i = 0; i < presets.length(); i++) {
                JSONObject preset = presets.getJSONObject(i);
                String role = preset.getString("role");
                if (role.equals("Leveling")) continue;
                String goal = role.equals("PvP") ? "pvp" : role.equals("Tank") ? "dungeons" : "raid";
                builds.add(candidate(preset.getString("id"), preset.getString("title"), goal,
                    role, preset.getString("code"), layout));
            }
            return nonempty(builds);
        }
        throw new IOException("Preset guide missing");
    }

    private static JSONArray arrayAfter(String text, String marker) throws Exception {
        int start = text.indexOf(marker);
        if (start < 0) throw new IOException("Guide section missing");
        return (JSONArray) new JSONTokener(text.substring(start + marker.length())).nextValue();
    }

    private static JSONArray layout(JSONArray trees, boolean sort) throws Exception {
        if (trees.length() != 3) throw new IOException("Unsupported guide layout");
        JSONArray result = new JSONArray();
        for (int i = 0; i < trees.length(); i++) {
            JSONArray talents = trees.getJSONObject(i).getJSONArray("talents");
            List<JSONObject> sorted = new ArrayList<>();
            for (int j = 0; j < talents.length(); j++) sorted.add(talents.getJSONObject(j));
            if (sort) sorted.sort(java.util.Comparator.comparingInt(node -> node.optInt("row") * 4 + node.optInt("col")));
            JSONArray ids = new JSONArray();
            for (JSONObject node : sorted) ids.put(node.getInt("id"));
            result.put(ids);
        }
        return result;
    }

    private static JSONObject candidate(String key, String title, String goal, String role, String code,
            JSONArray layout) throws Exception {
        return new JSONObject().put("sourceKey", key).put("title", title).put("goal", goal)
            .put("role", role).put("ranks", decode(code, layout));
    }

    private static JSONObject decode(String code, JSONArray layout) throws Exception {
        JSONObject result = new JSONObject();
        String[] groups = code.split("-", -1);
        if (groups.length > 3) return result;
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            JSONArray ids = layout.getJSONArray(i);
            if (group.length() > ids.length() || !group.matches("[0-9]*")) return new JSONObject();
            for (int j = 0; j < group.length(); j++) {
                int rank = group.charAt(j) - '0';
                if (rank > 0) result.put(Integer.toString(ids.getInt(j)), rank);
            }
        }
        return result;
    }

    private static List<JSONObject> nonempty(List<JSONObject> builds) throws IOException {
        builds.removeIf(build -> build.has("ranks") && build.optJSONObject("ranks").length() == 0);
        if (builds.isEmpty()) throw new IOException("Guide contains no recognizable builds");
        return builds;
    }
}
