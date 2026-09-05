package io.stamethyst.compatmod.save;

import basemod.BaseMod;
import basemod.abstracts.CustomSavable;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.saveAndContinue.SaveFile;

import java.util.ArrayList;

public final class ShrineListSaveField implements CustomSavable<ArrayList<String>> {
    private static final String SAVE_KEY = "amethystruntimecompat:shrine_list_v1";
    private static final OrderedStringListSaveState STATE = new OrderedStringListSaveState();
    private static final ShrineListSaveField INSTANCE = new ShrineListSaveField();

    private ShrineListSaveField() {
    }

    public static void initialize() {
        BaseMod.addSaveField(SAVE_KEY, INSTANCE);
    }

    @Override
    public ArrayList<String> onSave() {
        return STATE.snapshot(AbstractDungeon.shrineList);
    }

    @Override
    public void onLoad(ArrayList<String> shrineList) {
        STATE.load(shrineList);
    }

    private static void restoreAfterDungeonLoad() {
        ArrayList<String> savedShrines = STATE.consume();
        if (savedShrines == null || AbstractDungeon.shrineList == null) {
            return;
        }
        AbstractDungeon.shrineList.clear();
        AbstractDungeon.shrineList.addAll(savedShrines);
    }

    @SpirePatch2(
        clz = AbstractDungeon.class,
        method = "<ctor>",
        paramtypez = {String.class, AbstractPlayer.class, SaveFile.class}
    )
    public static class LoadConstructorPatch {
        @SpirePostfixPatch
        public static void Postfix() {
            restoreAfterDungeonLoad();
        }
    }
}
