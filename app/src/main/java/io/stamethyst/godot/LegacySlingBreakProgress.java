package io.stamethyst.godot;

import java.util.*;
import org.json.*;

/** Adapter for the Web v1 state to the native version-1 progress envelope. */
final class LegacySlingBreakProgress {
    private static final Set<String> SKILLS = words("trident echo titan piercer meteor hunters seeking ricochet blast lightning ice shatter critical execute rage sweep lance pulse aftershock storm blizzard prism alchemist mint treasury bargain forge resonance corehunter legion decay rapid heavy growing doubletap poison nova crossfire jackpot snowburst supernova swarmqueen railgun ambush siphon opportunist sharpshooter bounty bankshot minefield frostfire thunderlottery mirror specialist reaper cascade orbital roulette stormfront boomerang wormhole buzzsaw siegebreaker spectral transmute threadweaver infection contract timeslip nailburst fusepath rhythm reboundaim overkill pendulum teslanet undertow chronicle firewheel worldfold starforge");
    private static final Set<String> BRICKS = words("normal bomb lightning frost prism gold void hydra shard anchor plate magma scale star hour");
    private static final Set<String> BOSSES = words("eye clock serpent forge");

    static JSONObject convert(String raw) throws JSONException {
        require(raw != null && raw.length() <= LegacySlingBreakSaveService.MAX_LENGTH);
        JSONObject state = new JSONObject(raw);
        number(state, "level", 1, 100000, true);
        for (String key : words("coins total best")) number(state, key, 0, 1e18, !key.equals("coins"));
        JSONObject up = state.getJSONObject("up");
        for (String key : words("power arrow brick draw comboCap slots rerollCap")) {
            int max = key.equals("draw") ? 10 : key.equals("slots") ? 3 : key.equals("rerollCap") ? 4 : 10000;
            if (!up.has(key)) up.put(key, 0);
            number(up, key, 0, max, true);
        }
        JSONObject skills = state.has("skills") ? state.getJSONObject("skills") : new JSONObject();
        require(skills.length() <= 81);
        for (String id : keys(skills)) {
            require(SKILLS.contains(id));
            number(skills, id, 0, 10000, true);
            // The oldest Web saves accumulated ranks; native skills are single-rank.
            skills.put(id, skills.getDouble(id) > 0 ? 1 : 0);
        }
        state.put("skills", skills);
        for (String key : words("sound performanceMode")) if (state.has(key)) require(state.get(key) instanceof Boolean);
        if (!state.has("volume")) state.put("volume", state.optBoolean("sound", true) ? 100 : 0);
        number(state, "volume", 0, 100, true);
        for (String key : words("rerolls rerollGrantLevel skillChosenLevel legacyBest comboRulesVersion skillScopeVersion skillGateVersion")) {
            if (state.has(key)) number(state, key, 0, 1e18, true);
        }
        if (state.has("achievements")) {
            JSONObject achievements = state.getJSONObject("achievements");
            require(achievements.length() <= 32);
            for (String key : keys(achievements)) number(achievements, key, 0, 1e18, true);
        }
        if (!state.isNull("board")) board(state.getJSONObject("board"));
        if (!state.isNull("skillRuntime")) runtime(state.getJSONObject("skillRuntime"));
        for (String key : words("draft swapOffer")) if (!state.isNull(key)) {
            JSONObject offer = state.getJSONObject(key);
            number(offer, "level", 1, 100000, true);
            JSONArray options = offer.getJSONArray("options");
            require(options.length() == 3);
            Set<String> unique = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                Object id = options.get(i);
                require(id instanceof String && SKILLS.contains(id) && unique.add((String) id));
            }
            if (key.equals("draft") && offer.has("rerolled")) number(offer, "rerolled", 0, 1e18, true);
            if (key.equals("swapOffer")) require(SKILLS.contains(offer.get("target")));
        }
        if (!state.isNull("bossOverride")) {
            JSONObject boss = state.getJSONObject("bossOverride");
            number(boss, "level", 1, 100000, true); require(BOSSES.contains(boss.get("boss")));
        }
        if (state.has("bossDeck")) {
            JSONArray deck = state.getJSONArray("bossDeck"); require(deck.length() <= 4);
            for (int i = 0; i < deck.length(); i++) require(BOSSES.contains(deck.get(i)));
        }
        if (state.has("variantDeck")) {
            JSONArray deck = state.getJSONArray("variantDeck"); require(deck.length() <= 64);
            for (int i = 0; i < deck.length(); i++) require(deck.get(i) instanceof String && deck.getString(i).length() <= 24);
        }
        if (state.has("variantLast")) require(state.get("variantLast") instanceof String && state.getString("variantLast").length() <= 24);
        if (!state.isNull("variantOverride")) {
            JSONObject variant = state.getJSONObject("variantOverride");
            number(variant, "level", 1, 100000, true); require(variant.get("id") instanceof String);
        }
        if (state.has("paceShots")) number(state, "paceShots", 0, 1e6, false);
        // These native-only schemas need an engine-side importer, not a guessed conversion.
        require(state.isNull("nativeBoss"));
        return new JSONObject().put("format", "slingbreak-progress").put("version", 1).put("state", state);
    }

    private static void runtime(JSONObject runtime) throws JSONException {
        for (String key : words("level shots")) if (runtime.has(key)) number(runtime, key, 0, 1e18, true);
        for (String key : words("forge decay")) if (runtime.has(key)) require(runtime.get(key) instanceof Boolean);
        if (runtime.has("contract")) {
            JSONObject contract = runtime.getJSONObject("contract");
            number(contract, "rounds", 0, 3, true); number(contract, "size", 0, 3, true);
            JSONArray targets = contract.getJSONArray("targets"); require(targets.length() <= 3);
            for (int i = 0; i < targets.length(); i++) {
                JSONObject point = targets.getJSONObject(i);
                number(point, "x", -2000, 2000, false); number(point, "y", -2000, 2000, false);
            }
        }
    }

    private static void board(JSONObject board) throws JSONException {
        number(board, "initial", 1, 10000, true); number(board, "killed", 0, 10000, true);
        if (board.has("level")) number(board, "level", 1, 100000, true);
        if (board.has("levelMoney")) number(board, "levelMoney", 0, 1e18, false);
        if (board.has("core")) require(board.get("core") instanceof Boolean);
        // Web v1 predates native variant rule saves. Leave an unsupported save untouched.
        require(!board.has("variant"));
        JSONArray bricks = board.getJSONArray("bricks"); require(bricks.length() <= 240);
        for (int i = 0; i < bricks.length(); i++) {
            JSONObject brick = bricks.getJSONObject(i);
            for (String key : words("x y w h hp max")) number(brick, key, key.equals("x") || key.equals("y") ? -2000 : .001, 1e12, false);
            require(BRICKS.contains(brick.get("type")));
            if (brick.has("frozen")) require(brick.get("frozen") instanceof Boolean);
            if (brick.has("nativeFlags")) {
                JSONObject flags = brick.getJSONObject("nativeFlags"); require(flags.length() <= 16);
                for (String key : keys(flags)) {
                    if (key.equals("markUntil")) number(flags, key, 0, 1e18, false);
                    else if (words("hourIndex slot count segment").contains(key)) number(flags, key, 0, 64, true);
                    else require(flags.get(key) instanceof Boolean);
                }
            }
        }
        if (board.has("obstacles")) {
            JSONArray obstacles = board.getJSONArray("obstacles"); require(obstacles.length() <= 12);
            for (int i = 0; i < obstacles.length(); i++) {
                JSONObject obstacle = obstacles.getJSONObject(i);
                for (String key : words("x y w h")) number(obstacle, key, key.equals("x") || key.equals("y") ? -2000 : .001, 2000, false);
            }
        }
    }

    private static void number(JSONObject object, String key, double min, double max, boolean integer) throws JSONException {
        Object value = object.get(key);
        require(value instanceof Number);
        double n = ((Number) value).doubleValue();
        require(Double.isFinite(n) && n >= min && n <= max && (!integer || n == Math.floor(n)));
    }
    private static void require(boolean valid) throws JSONException {
        if (!valid) throw new JSONException("Unsupported or invalid legacy SlingBreak progress");
    }
    private static Set<String> words(String text) { return new HashSet<>(Arrays.asList(text.split(" "))); }
    private static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>(); object.keys().forEachRemaining(result::add); return result;
    }
}
