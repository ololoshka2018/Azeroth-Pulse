package ru.ivan.azerothpulse;

import static org.junit.Assert.*;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class AppUpdatesTest {
    private JSONObject release() throws Exception {
        JSONObject apk = new JSONObject().put("name", "Azeroth-Pulse-0.17.0.apk")
            .put("state", "uploaded").put("size", 33_000_000)
            .put("browser_download_url", "https://github.com/" + AppUpdates.REPOSITORY
                + "/releases/download/v0.17.0/Azeroth-Pulse-0.17.0.apk");
        return new JSONObject().put("tag_name", "v0.17.0").put("draft", false).put("prerelease", false)
            .put("published_at", "2026-11-07T12:00:00Z").put("body", "Release notes")
            .put("assets", new JSONArray().put(apk));
    }

    @Test public void acceptsMatchingOwnerRelease() throws Exception {
        assertEquals("0.17.0", AppUpdates.parse(release()).getString("version"));
        assertTrue(AppUpdates.compare("0.17.0", "0.16.0") > 0);
        assertTrue(AppUpdates.compare("0.9.9", "0.16.0") < 0);
    }

    @Test public void rejectsDraftsAndPreviews() throws Exception {
        for (String field : new String[]{"draft", "prerelease"}) {
            JSONObject release = release().put(field, true);
            assertThrows(IOException.class, () -> AppUpdates.parse(release));
        }
    }

    @Test public void rejectsDifferentRepositoryAndAsset() throws Exception {
        JSONObject foreign = release();
        foreign.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.org/update.apk");
        assertThrows(IOException.class, () -> AppUpdates.parse(foreign));
        JSONObject wrongFile = release();
        wrongFile.getJSONArray("assets").getJSONObject(0).put("name", "Different.apk");
        assertThrows(IOException.class, () -> AppUpdates.parse(wrongFile));
        assertThrows(IOException.class, () -> AppUpdates.parse(release().put("assets", new JSONArray())));
    }
}
