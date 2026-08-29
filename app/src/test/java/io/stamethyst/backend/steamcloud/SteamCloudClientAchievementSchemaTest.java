package io.stamethyst.backend.steamcloud;

import static org.junit.Assert.assertEquals;

import in.dragonbra.javasteam.types.KeyValue;
import java.util.List;
import org.junit.Test;

public final class SteamCloudClientAchievementSchemaTest {
    @Test
    public void parsesIdKeyedAchievementDefinitions() {
        KeyValue root = new KeyValue("UserStats");
        KeyValue achievements = new KeyValue("achievements");
        KeyValue shrugItOff = new KeyValue("17");
        shrugItOff.set("name", new KeyValue("name", "shrug_it_off"));
        shrugItOff.set("displayName", new KeyValue("displayName", "Shrug It Off"));
        achievements.set("17", shrugItOff);
        root.set("achievements", achievements);

        List<SteamCloudClient.UserStatsResult.AchievementDefinition> definitions =
            SteamCloudClient.parseAchievementSchema(root);

        assertEquals(1, definitions.size());
        assertEquals(17, definitions.get(0).achievementId);
        assertEquals("shrug_it_off", definitions.get(0).apiName);
    }

    @Test
    public void keepsLegacyAchievementDefinitionSupport() {
        KeyValue root = new KeyValue("UserStats");
        KeyValue achievement = new KeyValue("achievement");
        achievement.set("id", new KeyValue("id", "5"));
        achievement.set("name", new KeyValue("name", "shrug_it_off"));
        root.set("achievement", achievement);

        List<SteamCloudClient.UserStatsResult.AchievementDefinition> definitions =
            SteamCloudClient.parseAchievementSchema(root);

        assertEquals(1, definitions.size());
        assertEquals(5, definitions.get(0).achievementId);
        assertEquals("shrug_it_off", definitions.get(0).apiName);
    }

    @Test
    public void parsesAchievementBitfieldStatTarget() {
        KeyValue root = new KeyValue("UserStats");
        KeyValue stats = new KeyValue("stats");
        KeyValue stat = new KeyValue("1");
        KeyValue bits = new KeyValue("bits");
        KeyValue bit = new KeyValue("1");
        bit.set("name", new KeyValue("name", "shrug_it_off"));
        bits.set("1", bit);
        stat.set("bits", bits);
        stats.set("1", stat);
        root.set("stats", stats);

        SteamCloudClient.UserStatsResult.AchievementStatTarget target =
            SteamCloudClient.parseAchievementStatTargets(root).get("shrug_it_off");

        assertEquals(1, target.statId);
        assertEquals(1, target.bitIndex);
        assertEquals(2, target.mask);
    }

    @Test
    public void parsesAchievementNameFromDisplayWhenBitHasNoDirectName() {
        KeyValue root = new KeyValue("UserStats");
        KeyValue stats = new KeyValue("stats");
        KeyValue stat = new KeyValue("1");
        KeyValue bits = new KeyValue("bits");
        KeyValue bit = new KeyValue("1");
        KeyValue display = new KeyValue("display");
        KeyValue displayName = new KeyValue("name");
        displayName.set("english", new KeyValue("english", "shrug_it_off"));
        display.set("name", displayName);
        bit.set("display", display);
        bits.set("1", bit);
        stat.set("bits", bits);
        stats.set("1", stat);
        root.set("stats", stats);

        SteamCloudClient.UserStatsResult.AchievementStatTarget target =
            SteamCloudClient.parseAchievementStatTargets(root).get("shrug_it_off");

        assertEquals(1, target.statId);
        assertEquals(1, target.bitIndex);
    }

    @Test
    public void ignoresSchemaEntriesWithNullNamesWithoutThrowing() {
        KeyValue root = new KeyValue("UserStats");
        KeyValue achievements = new KeyValue("achievements");
        KeyValue achievement = new KeyValue("3");
        achievement.set("name", new KeyValue("name"));
        achievements.set("3", achievement);
        root.set("achievements", achievements);

        KeyValue stats = new KeyValue("stats");
        KeyValue stat = new KeyValue("1");
        KeyValue bits = new KeyValue("bits");
        KeyValue bit = new KeyValue("1");
        bit.set("name", new KeyValue("name"));
        bits.set("1", bit);
        stat.set("bits", bits);
        stats.set("1", stat);
        root.set("stats", stats);

        assertEquals(0, SteamCloudClient.parseAchievementSchema(root).size());
        assertEquals(0, SteamCloudClient.parseAchievementStatTargets(root).size());
    }

}
