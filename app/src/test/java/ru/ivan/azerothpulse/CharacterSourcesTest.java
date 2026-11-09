package ru.ivan.azerothpulse;
import static org.junit.Assert.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
public final class CharacterSourcesTest {
    private String fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/armory-character.html")) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test public void parsesRealBlizzardPublicProfile() throws Exception {
        JSONObject c = CharacterSources.profile(fixture(),"EU","judgement-judy");
        assertEquals("Judgement",c.getString("name")); assertEquals("Paladin",c.getString("className"));
        assertEquals(24,c.getInt("level")); assertTrue(c.getJSONArray("professions").length()>0);
        assertEquals(1,c.getJSONArray("gear").length()); assertTrue(c.getJSONArray("stats").length()>0);
        assertEquals("https://render.worldofwarcraft.com/forever-eu/icons/56/135312.jpg",
            c.getJSONArray("gear").getJSONObject(0).getString("icon"));
        assertTrue(c.getString("render").endsWith("-main-raw.png"));
        JSONArray stats = c.getJSONArray("stats");
        Map<String, JSONObject> byType = new HashMap<>();
        for (int i = 0; i < stats.length(); i++) byType.put(stats.getJSONObject(i).getString("type"), stats.getJSONObject(i));
        assertEquals(108, byType.get("DAMAGE").getDouble("min"), 0.001);
        assertEquals(136, byType.get("DAMAGE").getDouble("max"), 0.001);
        assertEquals(53, byType.get("SPIRIT").getInt("value"));
        assertEquals("Circle of Lore",c.getString("guild")); assertTrue(c.getString("avatar").startsWith("https://render.worldofwarcraft.com/forever-eu/"));
    }
    @Test public void readsTransmogWithoutReplacingEquippedItem() throws Exception {
        String html = fixture().replace("\"context\": 1,", "\"context\": 1, \"transmog\": {\"item\": {\"id\": 2763, \"name\": \"Fisherman Knife\"}},");
        JSONObject item = CharacterSources.profile(html,"EU","judgement-judy").getJSONArray("gear").getJSONObject(0);
        assertEquals("Sword of the Fallen",item.getString("name"));
        assertEquals(2763,item.getJSONObject("transmog").getInt("id"));
        assertEquals("Fisherman Knife",item.getJSONObject("transmog").getString("name"));
    }
    @Test public void rejectsOtherRegionAndCharacter() throws Exception {
        assertThrows(IOException.class,()->CharacterSources.profile(fixture(),"US","judgement-judy"));
        assertThrows(IOException.class,()->CharacterSources.profile(fixture(),"EU","wrong-character"));
    }
    @Test public void restrictsImageHostsAndNames() throws Exception {
        assertEquals("judgement-judy",CharacterSources.slug("Judgement Judy"));
        assertEquals("иван-егоров",CharacterSources.slug("Иван Егоров"));
        assertThrows(IOException.class,()->CharacterSources.slug("../../secret"));
        assertFalse(CharacterSources.allowedImage("https://render.worldofwarcraft.com.evil.test/forever-eu/character/x.jpg"));
        assertFalse(CharacterSources.allowedImage("file:///private.jpg"));
        assertFalse(CharacterSources.allowedImage("https://render.worldofwarcraft.com/retail/character/x.jpg"));
    }
    @Test public void missingArmoryModelIsAnError() {
        assertThrows(IOException.class,()->CharacterSources.profile("<html>Unavailable</html>","EU","test-hero"));
    }
}
