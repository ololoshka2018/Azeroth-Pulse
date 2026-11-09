package ru.ivan.azerothpulse;
import static org.junit.Assert.*;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
public final class ForeverSourcesTest {
    private String page(String rows) {
        return "<div class='stamp'>Scanned: Nov 8, 2026 · Page generated: 2026-11-08 12:00Z</div><script>window.ROUTE={\"edition\":\"forever\",\"page\":\"realms\"};const REALMS=" + rows + ";</script>";
    }
    private String row(String region, String type) {
        return "{\"groupRegion\":\""+region+"\",\"realmType\":\""+type+"\",\"id\":\""+region.toLowerCase()+":normal\",\"known\":true,\"seen\":1000,\"d24\":0,\"A\":600,\"H\":400,\"cls\":{\"Rogue\":20}}";
    }
    @Test public void parsesActualNativeCensusAndKeepsRegionsSeparate() throws Exception {
        JSONObject data = ForeverSources.census(page("["+row("EU","Normal")+","+row("US","PvP")+"]"),"EU");
        assertEquals(1,data.length());
        assertEquals(1000,data.getJSONObject("PVE").getInt("total"));
        assertEquals(0,data.getJSONObject("PVE").getInt("active24h"));
        assertTrue(data.getJSONObject("PVE").isNull("active7d"));
        assertEquals(20,data.getJSONObject("PVE").getJSONObject("classes").getInt("Rogue"));
    }
    @Test public void rejectsBetaAndDuplicateGroups() {
        String current = page("["+row("EU","Normal")+"]");
        assertThrows(IOException.class,()->ForeverSources.census(current.replace("2026-11-08","2026-10-08").replace("Nov 8","Oct 8"),"EU"));
        assertThrows(IOException.class,()->ForeverSources.census(page("["+row("EU","Normal")+","+row("EU","Normal")+"]"),"EU"));
    }
    @Test public void keepsNewerCachedSnapshotWhenServerRegresses() throws Exception {
        JSONObject saved = new JSONObject().put("PVE",new JSONObject().put("publishedAt","2026-11-08T12:00:00Z").put("total",500));
        JSONObject stale = new JSONObject().put("PVE",new JSONObject().put("publishedAt","2026-11-07T12:00:00Z").put("total",100));
        ForeverSources.merge(saved,stale,"publishedAt"); assertEquals(500,saved.getJSONObject("PVE").getInt("total"));
    }
    @Test public void untranslatedNewsKeepsOriginalTitleAndSummary() throws Exception {
        JSONObject article = new JSONObject().put("title","New patch").put("summary","Changes").put("titleRu","");
        assertEquals("New patch",NewsBoard.display(article,"title"));
        article.put("titleRu","Новое обновление"); assertEquals("Новое обновление",NewsBoard.display(article,"title"));
        assertEquals("Changes",NewsBoard.display(article,"summary"));
    }
}
