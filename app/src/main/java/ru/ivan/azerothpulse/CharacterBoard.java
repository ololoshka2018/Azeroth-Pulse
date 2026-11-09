package ru.ivan.azerothpulse;

import android.content.SharedPreferences;
import android.os.Handler;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONObject;

final class CharacterBoard {
    private final SharedPreferences preferences;
    private final ExecutorService worker;
    private final Handler main;
    private final Runnable changed;
    private JSONArray characters, results = new JSONArray();
    private String selected, status = "saved", error = "", query = "", searchRegion = "EU";
    private boolean closed;
    private int ticket;

    CharacterBoard(SharedPreferences preferences, ExecutorService worker, Handler main, Runnable changed) {
        this.preferences = preferences; this.worker = worker; this.main = main; this.changed = changed;
        try { characters = new JSONArray(preferences.getString("charactersV1", "[]")); }
        catch (Exception invalid) { characters = new JSONArray(); }
        selected = preferences.getString("selectedCharacterV1", "");
    }
    JSONObject selected() {
        for (int i = 0; i < characters.length(); i++) {
            JSONObject character = characters.optJSONObject(i);
            if (character != null && selected.equals(character.optString("key"))) return character;
        }
        return null;
    }
    String context() { return selected() == null ? "" : selected; }
    JSONObject state() throws Exception {
        return new JSONObject().put("characters", characters).put("selected", selected).put("results", results)
            .put("status", status).put("error", error).put("query", query).put("searchRegion", searchRegion);
    }
    void search(String region, String query) {
        if (closed || query == null || query.trim().length() < 2 || query.length() > 80) return;
        try { CharacterSources.region(region); } catch (Exception invalid) { return; }
        this.query = query.trim(); this.searchRegion = region;
        int request = ++ticket; status = "searching"; error = ""; results = new JSONArray(); changed.run();
        String text = this.query;
        worker.execute(() -> {
            try {
                String url = "https://worldofwarcraft.blizzard.com/en-us/forever/" + CharacterSources.region(region)
                    + "/armory/character?q=" + URLEncoder.encode(text, "UTF-8");
                JSONArray found = CharacterSources.search(ForeverSources.download(url), region);
                main.post(() -> { if (!closed && request == ticket) { results = found; status = "checked"; changed.run(); } });
            } catch (Exception unavailable) { main.post(() -> failure(request, "Поиск Blizzard сейчас недоступен. Проверь соединение и повтори.")); }
        });
    }
    void load(String region, String slug) {
        if (closed) return;
        try { CharacterSources.region(region); CharacterSources.slug(slug); } catch (Exception invalid) { return; }
        int request = ++ticket; status = "loading"; error = ""; changed.run();
        worker.execute(() -> {
            try {
                JSONObject profile = CharacterSources.profile(ForeverSources.download(CharacterSources.profileUrl(region, slug)), region, slug);
                main.post(() -> {
                    if (closed || request != ticket) return;
                    JSONArray next = new JSONArray(); next.put(profile);
                    for (int i = 0; i < characters.length() && next.length() < 10; i++) {
                        JSONObject old = characters.optJSONObject(i);
                        if (old != null && !profile.optString("key").equals(old.optString("key"))) next.put(old);
                    }
                    characters = next; selected = profile.optString("key"); status = "checked"; save(); changed.run();
                });
            } catch (Exception unavailable) {
                main.post(() -> failure(request, unavailable instanceof java.io.FileNotFoundException
                    ? "Профиль пока недоступен. Проверь регион и полное имя. В Armory персонажи появляются с 10-го уровня после выхода из игры."
                    : "Профиль не обновился. Сохранённые данные доступны; повтори позже."));
            }
        });
    }
    void refresh() { JSONObject selected = selected(); if (selected != null) load(selected.optString("region"), selected.optString("slug")); }
    void select(String key) {
        if (closed) return;
        for (int i = 0; i < characters.length(); i++) if (key.equals(characters.optJSONObject(i).optString("key"))) {
            ticket++; selected = key; status = "saved"; error = ""; save(); changed.run(); return;
        }
    }
    void remove(String key) {
        if (closed) return;
        ticket++; JSONArray next = new JSONArray();
        for (int i = 0; i < characters.length(); i++) if (!key.equals(characters.optJSONObject(i).optString("key"))) next.put(characters.optJSONObject(i));
        characters = next;
        if (selected.equals(key)) selected = characters.length() == 0 ? "" : characters.optJSONObject(0).optString("key");
        status = "saved"; error = ""; save(); changed.run();
    }
    private void save() { preferences.edit().putString("charactersV1", characters.toString()).putString("selectedCharacterV1", selected).apply(); }
    private void failure(int request, String message) { if (!closed && request == ticket) { status = "offline"; error = message; changed.run(); } }
    void close() { closed = true; ticket++; }
}
