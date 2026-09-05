package io.stamethyst.compatmod.save;

import basemod.BaseMod;
import basemod.abstracts.CustomSavable;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.EventRoom;
import com.megacrit.cardcrawl.rooms.ShopRoom;
import com.megacrit.cardcrawl.saveAndContinue.SaveFile;

public final class EventRollPriorRoomSaveField implements CustomSavable<Boolean> {
    private static final String SAVE_KEY = "amethystruntimecompat:event_roll_prior_room_shop_v1";
    private static final EventRollPriorRoomState STATE = new EventRollPriorRoomState();
    private static final EventRollPriorRoomSaveField INSTANCE = new EventRollPriorRoomSaveField();
    private static SaveFile.SaveType activeSaveType;

    private EventRollPriorRoomSaveField() {
    }

    public static void initialize() {
        BaseMod.addSaveField(SAVE_KEY, INSTANCE);
    }

    @Override
    public Boolean onSave() {
        AbstractRoom currentRoom = AbstractDungeon.getCurrRoom();
        boolean postCombatQuestionRoom = activeSaveType == SaveFile.SaveType.POST_COMBAT
            && currentRoom != null
            && (currentRoom instanceof EventRoom || currentRoom.combatEvent);
        return STATE.valueForSave(postCombatQuestionRoom, currentRoom instanceof ShopRoom);
    }

    @Override
    public void onLoad(Boolean priorRoomWasShop) {
        STATE.load(priorRoomWasShop);
    }

    static boolean prepareEventRoll(boolean loadingSave, boolean currentRoomWasShop) {
        return STATE.prepareRoll(loadingSave, currentRoomWasShop);
    }

    @SpirePatch2(
        clz = SaveFile.class,
        method = "<ctor>",
        paramtypez = {SaveFile.SaveType.class}
    )
    public static class SaveFileConstructorPatch {
        @SpirePrefixPatch
        public static void Prefix(Object[] __args) {
            activeSaveType = (SaveFile.SaveType)__args[0];
        }
    }
}
