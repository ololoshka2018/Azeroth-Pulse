package ru.ivan.azerothpulse;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class TalentChangesTest {
    private JSONObject catalog() throws Exception {
        JSONObject node = new JSONObject().put("id", 1).put("en", "Talent").put("name", "Точность")
            .put("row", 0).put("col", 0).put("max", 3)
            .put("effects", new JSONArray().put(new JSONObject().put("fingerprint", "effect-one")));
        JSONObject tree = new JSONObject().put("en", "Combat").put("nodes", new JSONArray().put(node));
        JSONObject build = new JSONObject().put("source", "mob").put("sourceKey", "Combat")
            .put("goal", "leveling").put("title", "Бой").put("order", new JSONArray("[1,1,2]"));
        return new JSONObject().put("version", "1.60.1.70790")
            .put("trees", new JSONArray().put(tree)).put("builds", new JSONArray().put(build));
    }

    @Test public void ignoresRefreshTimeAndCacheFlags() throws Exception {
        JSONObject same = catalog().put("checked", "2026-11-07T12:00:00Z").put("partial", true);
        same.getJSONArray("builds").getJSONObject(0).put("cached", true).put("checked", "now");
        assertEquals(0, TalentChanges.diff(catalog(), same).length());
    }

    @Test public void recordsRanksOrderAndEffects() throws Exception {
        JSONObject ranks = catalog();
        ranks.getJSONArray("builds").getJSONObject(0).put("order", new JSONArray("[1,2,2]"));
        assertTrue(TalentChanges.diff(catalog(), ranks).toString().contains("изменены очки"));
        JSONObject order = catalog();
        order.getJSONArray("builds").getJSONObject(0).put("order", new JSONArray("[2,1,1]"));
        assertTrue(TalentChanges.diff(catalog(), order).toString().contains("порядок"));
        JSONObject effects = catalog();
        effects.getJSONArray("trees").getJSONObject(0).getJSONArray("nodes").getJSONObject(0)
            .getJSONArray("effects").getJSONObject(0).put("fingerprint", "effect-two");
        assertTrue(TalentChanges.diff(catalog(), effects).toString().contains("описания"));
    }

    @Test public void doesNotReportRemovalDuringPartialOutage() throws Exception {
        JSONObject missing = catalog().put("builds", new JSONArray()).put("partial", true);
        assertEquals(0, TalentChanges.diff(catalog(), missing).length());
        missing.put("partial", false);
        assertTrue(TalentChanges.diff(catalog(), missing).toString().contains("Удалён"));
    }

    @Test public void persistsReadStateAndAvoidsDuplicateChanges() throws Exception {
        Map<String, Object> stored = new HashMap<>();
        SharedPreferences preferences = preferences(stored);
        TalentChanges tracker = new TalentChanges(preferences);
        JSONObject changed = catalog();
        changed.getJSONArray("builds").getJSONObject(0).put("order", new JSONArray("[2,1,1]"));
        tracker.saveCatalog("Rogue", catalog(), changed);
        tracker = new TalentChanges(preferences);
        assertEquals(1, tracker.state().getInt("unread"));
        assertNotNull(stored.get("Rogue"));
        tracker.saveCatalog("Rogue", changed, new JSONObject(changed.toString()).put("checked", "later"));
        assertEquals(1, tracker.state().getInt("unread"));
        tracker.viewed(9);
        assertEquals(1, tracker.state().getInt("unread"));
        tracker.viewed(1);
        assertEquals(0, tracker.state().getInt("unread"));
        assertEquals(1, tracker.state().getJSONArray("entries").length());
    }

    private static SharedPreferences preferences(Map<String, Object> stored) {
        return (SharedPreferences) Proxy.newProxyInstance(TalentChangesTest.class.getClassLoader(),
            new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getString": case "getLong": return stored.getOrDefault((String) args[0], args[1]);
                    case "edit":
                        Map<String, Object> pending = new HashMap<>();
                        return Proxy.newProxyInstance(TalentChangesTest.class.getClassLoader(),
                            new Class[]{SharedPreferences.Editor.class}, (editor, action, values) -> {
                                if (action.getName().startsWith("put")) {
                                    pending.put((String) values[0], values[1]);
                                    return editor;
                                }
                                if ("apply".equals(action.getName())) { stored.putAll(pending); return null; }
                                throw new UnsupportedOperationException(action.getName());
                            });
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
    }
}
