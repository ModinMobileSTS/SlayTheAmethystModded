package io.stamethyst.compatmod.save;

import com.evacipated.cardcrawl.modthespire.lib.ByRef;
import com.evacipated.cardcrawl.modthespire.lib.LineFinder;
import com.evacipated.cardcrawl.modthespire.lib.Matcher;
import com.evacipated.cardcrawl.modthespire.lib.SpireInsertLocator;
import com.evacipated.cardcrawl.modthespire.lib.SpireInsertPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.EventHelper;
import com.megacrit.cardcrawl.random.Random;
import com.megacrit.cardcrawl.rooms.ShopRoom;

import javassist.CtBehavior;

public final class EventHelperReplayShopContextPatches {
    private EventHelperReplayShopContextPatches() {
    }

    @SpirePatch2(
        clz = EventHelper.class,
        method = "roll",
        paramtypez = {Random.class}
    )
    public static class EventHelperRollPatch {
        @SpireInsertPatch(locator = PriorRoomShopCheckLocator.class, localvars = {"shopSize"})
        public static void Insert(@ByRef int[] shopSize) {
            boolean currentRoomWasShop = AbstractDungeon.getCurrRoom() instanceof ShopRoom;
            if (EventRollPriorRoomSaveField.prepareEventRoll(
                CardCrawlGame.loadingSave,
                currentRoomWasShop
            )) {
                shopSize[0] = 0;
            }
        }
    }

    private static class PriorRoomShopCheckLocator extends SpireInsertLocator {
        @Override
        public int[] Locate(CtBehavior method) throws Exception {
            Matcher matcher = new Matcher.MethodCallMatcher(AbstractDungeon.class, "getCurrRoom");
            return LineFinder.findInOrder(method, matcher);
        }
    }
}
