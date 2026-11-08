package ru.ivan.azerothpulse;

import java.io.IOException;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

final class TalentStats {
    static final String URL = "https://wowcensus.io/talents";
    private static final Pattern DATA = Pattern.compile("window\\.TALENT_DATA\\s*=\\s*");
    private static final Pattern DATE = Pattern.compile("latest inspection (\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2})Z");
    static final String[] CLASSES = {
        "Warrior", "Paladin", "Hunter", "Rogue", "Priest", "Shaman", "Mage", "Warlock", "Druid"
    };

    private TalentStats() {}

    static boolean isClass(String value) {
        for (String name : CLASSES) if (name.equals(value)) return true;
        return false;
    }

    static JSONObject parse(String html) throws Exception {
        Document page = Jsoup.parse(html);
        Matcher date = DATE.matcher(page.text());
        if (!date.find()) throw new IOException("Unknown talent census date");
        Instant updated = Instant.parse(date.group(1) + "T" + date.group(2) + ":00Z");
        if (updated.isBefore(Instant.parse("2026-11-04T23:00:00Z"))) throw new IOException("Pre-launch data");
        for (Element script : page.select("script:not([src])")) {
            Matcher data = DATA.matcher(script.data());
            if (!data.find()) continue;
            JSONObject raw = (JSONObject) new JSONTokener(script.data().substring(data.end())).nextValue();
            JSONObject byFaction = raw.getJSONObject("builds");
            JSONObject totals = raw.getJSONObject("totals");
            JSONObject classes = new JSONObject();
            for (String name : CLASSES) {
                int sample = totals.optInt(name, 0);
                if (sample <= 0 || sample > 10_000_000) continue;
                JSONObject specs = new JSONObject();
                int sum = 0;
                for (String faction : new String[]{"A", "H"}) {
                    JSONArray rows = byFaction.getJSONArray(faction);
                    for (int i = 0; i < rows.length(); i++) {
                        JSONArray row = rows.getJSONArray(i);
                        if (!name.equals(row.getString(0))) continue;
                        String spec = row.getString(1);
                        int count = row.getInt(2);
                        if (spec.isEmpty() || spec.length() > 60 || count < 1 || count > sample) {
                            throw new IOException("Invalid talent census row");
                        }
                        specs.put(spec, specs.optInt(spec, 0) + count);
                        sum += count;
                    }
                }
                if (sum > sample) throw new IOException("Invalid talent census total");
                classes.put(name, new JSONObject().put("sample", sample).put("specs", specs));
            }
            if (classes.length() == 0) throw new IOException("Empty talent census");
            return new JSONObject().put("observedAt", updated.toString()).put("classes", classes);
        }
        throw new IOException("Talent census missing");
    }
}
