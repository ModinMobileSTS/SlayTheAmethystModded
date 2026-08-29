package io.stamethyst.compatmod.autoplay;

import com.badlogic.gdx.Gdx;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.helpers.MonsterHelper;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.MonsterRoom;
import com.megacrit.cardcrawl.saveAndContinue.SaveAndContinue;
import com.megacrit.cardcrawl.screens.charSelect.CharacterOption;
import com.megacrit.cardcrawl.screens.charSelect.CharacterSelectScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MenuButton;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

/**
 * Drives one configured combat room for harness verification.
 */
final class AutoplaySingleRoomRunner {
    private static final Random RNG = new Random();
    private static AutoplaySingleRoomSpec spec;
    private static boolean specLoadAttempted;
    private static boolean staleSavesDeleted;
    private static boolean roomConfigured;
    private static boolean roomConfigurationStarted;
    private static boolean roomConfigurationFailed;
    private static boolean resultLogged;

    private AutoplaySingleRoomRunner() {
    }

    static void tick() {
        if (resultLogged) {
            return;
        }
        AutoplaySingleRoomSpec activeSpec = loadSpecOnce();
        if (activeSpec == null) {
            return;
        }
        if (CardCrawlGame.mode != CardCrawlGame.GameMode.GAMEPLAY) {
            handleMenu(activeSpec);
            return;
        }
        handleGameplay(activeSpec);
    }

    private static AutoplaySingleRoomSpec loadSpecOnce() {
        if (spec != null || specLoadAttempted) {
            return spec;
        }
        specLoadAttempted = true;
        try {
            spec = AutoplaySingleRoomSpec.load(AutoplayConfig.getSingleRoomSpecPath());
            AutoplayLog.info(
                "single_room spec loaded character=" + spec.characterId
                    + " monster=" + spec.monsterId
                    + " cards=" + spec.describeCards()
            );
        } catch (Throwable t) {
            AutoplayLog.warn("single_room failed to load spec", t);
            logConfigError("spec_load_failed");
        }
        return spec;
    }

    private static void handleMenu(AutoplaySingleRoomSpec activeSpec) {
        MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
        if (menu == null || menu.isFadingOut || menu.fadedOut) {
            return;
        }
        if (menu.screen == MainMenuScreen.CurScreen.MAIN_MENU) {
            deleteStaleSavesOnce();
            removeStaleResumeButtons(menu);
            if (menu.charSelectScreen != null) {
                AutoplayLog.info("single_room opening character select");
                menu.charSelectScreen.open(false);
            }
            return;
        }
        if (menu.screen == MainMenuScreen.CurScreen.CHAR_SELECT) {
            handleCharacterSelect(menu.charSelectScreen, activeSpec);
        }
    }

    private static void handleCharacterSelect(
        CharacterSelectScreen screen,
        AutoplaySingleRoomSpec activeSpec
    ) {
        if (screen == null || screen.options == null || screen.confirmButton == null
            || screen.confirmButton.hb == null) {
            return;
        }
        CharacterOption chosen = findCharacterOption(screen, activeSpec);
        if (chosen == null) {
            AutoplayLog.warn(
                "single_room character not found character=" + activeSpec.characterId
                    + " available=" + describeCharacterOptions(screen),
                null
            );
            logConfigError("character_not_found");
            return;
        }
        if (chosen.locked) {
            AutoplayLog.warn("single_room character locked character=" + activeSpec.characterId, null);
            logConfigError("character_locked");
            return;
        }
        if (chosen.selected && isIncompleteNativeSelection(chosen)) {
            chosen.selected = false;
            AutoplayLog.info("single_room retrying incomplete character selection " + describe(chosen));
        }
        if (!chosen.selected) {
            queueCharacterOptionClick(chosen);
            return;
        }
        if (!screen.confirmButton.isDisabled && !screen.confirmButton.hb.clicked) {
            screen.confirmButton.hb.clicked = true;
            AutoplayLog.info("single_room pressed Embark character=" + describe(chosen));
        }
    }

    private static void handleGameplay(AutoplaySingleRoomSpec activeSpec) {
        AbstractRoom room = safeGetCurrentRoom();
        AbstractPlayer player = AbstractDungeon.player;
        if (room == null || player == null) {
            return;
        }
        if (!roomConfigured && (roomConfigurationStarted || roomConfigurationFailed)) {
            return;
        }
        if (!roomConfigured && room instanceof MonsterRoom) {
            configureRoom((MonsterRoom) room, player, activeSpec);
            return;
        }
        if (!roomConfigured) {
            AutoplayDungeonActions.tick();
            return;
        }
        // Keep the configured room alive for diagnostics only when hold mode is explicitly
        // requested. In bench mode (and default single-room) the autoplay turn loop runs.
        if (AutoplayConfig.isSingleRoomHoldEnabled()) {
            return;
        }
        // In bench mode, keep the player alive so the combat runs to completion.
        if (AutoplayConfig.isSingleRoomBenchModeEnabled() && player.currentHealth <= 0) {
            player.currentHealth = Math.max(player.maxHealth, 1);
        }
        if (player.isDead || player.currentHealth <= 0 || AbstractDungeon.screen == AbstractDungeon.CurrentScreen.DEATH) {
            logResult("player_dead", player, room);
            return;
        }
        if (room.monsters != null && room.monsters.areMonstersBasicallyDead()) {
            logResult("monsters_defeated", player, room);
            return;
        }
        if (AbstractDungeon.isScreenUp) {
            AutoplayChoiceScreenActions.handleOpenChoiceScreen("single_room");
            return;
        }
        if (room.phase == AbstractRoom.RoomPhase.COMBAT) {
            handleCombat(player, room);
        } else if (room.phase == AbstractRoom.RoomPhase.COMPLETE) {
            logResult("monsters_defeated", player, room);
        }
    }

    private static void configureRoom(
        MonsterRoom room,
        AbstractPlayer player,
        AutoplaySingleRoomSpec activeSpec
    ) {
        MonsterGroup monsters = createMonsterGroup(activeSpec.monsterId);
        if (monsters == null) {
            AutoplayLog.warn("single_room monster not found monster=" + activeSpec.monsterId, null);
            logConfigError("monster_not_found");
            return;
        }
        roomConfigurationStarted = true;
        String step = "start";
        try {
            step = "set_monster";
            AutoplayLog.info("single_room configure step=" + step);
            room.setMonster(monsters);
            room.monsters = monsters;
            room.phase = AbstractRoom.RoomPhase.COMBAT;
            room.rewardAllowed = false;
            room.rewardTime = false;
            room.isBattleOver = false;
            if (AbstractDungeon.actionManager == null) {
                AbstractDungeon.actionManager = new GameActionManager();
            }
            step = "clear_actions";
            AutoplayLog.info("single_room configure step=" + step);
            AbstractDungeon.actionManager.clear();
            step = "monster_init";
            AutoplayLog.info("single_room configure step=" + step);
            monsters.init();
            step = "monster_pre_battle";
            AutoplayLog.info("single_room configure step=" + step);
            monsters.usePreBattleAction();
            step = "monster_intent";
            AutoplayLog.info("single_room configure step=" + step);
            monsters.showIntent();
            step = "player_pre_battle";
            AutoplayLog.info("single_room configure step=" + step);
            player.preBattlePrep();
            step = "replace_hand";
            AutoplayLog.info("single_room configure step=" + step);
            if (!replaceHand(player, activeSpec)) {
                logConfigError("card_not_found");
                return;
            }
            roomConfigured = true;
            AutoplayLog.info(
                "single_room configured character=" + safePlayerClass(player)
                    + " monster=" + activeSpec.monsterId
                    + " cards=" + activeSpec.describeCards()
            );
        } catch (Throwable t) {
            AutoplayLog.warn("single_room failed to configure room step=" + step, t);
            logConfigError("configure_room_failed_" + step);
        }
    }

    private static MonsterGroup createMonsterGroup(String monsterId) {
        MonsterGroup group = getBaseModMonster(monsterId);
        if (group != null) {
            return group;
        }
        try {
            return MonsterHelper.getEncounter(monsterId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MonsterGroup getBaseModMonster(String monsterId) {
        try {
            Class<?> baseModClass = Class.forName("basemod.BaseMod");
            Method customMonsterExists = baseModClass.getMethod("customMonsterExists", String.class);
            Boolean exists = (Boolean) customMonsterExists.invoke(null, monsterId);
            if (exists == null || !exists.booleanValue()) {
                return null;
            }
            Method getMonster = baseModClass.getMethod("getMonster", String.class);
            return (MonsterGroup) getMonster.invoke(null, monsterId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean replaceHand(AbstractPlayer player, AutoplaySingleRoomSpec activeSpec) {
        ensureCardGroups(player);
        player.hand.clear();
        player.drawPile.clear();
        player.discardPile.clear();
        player.exhaustPile.clear();
        player.limbo.clear();
        boolean allCardsResolved = true;
        for (String cardId : activeSpec.cardIds) {
            AbstractCard card = getCardCopy(cardId);
            if (card == null) {
                AutoplayLog.warn("single_room card not found card=" + cardId, null);
                allCardsResolved = false;
                continue;
            }
            card.freeToPlayOnce = true;
            card.purgeOnUse = false;
            player.hand.addToHand(card);
        }
        player.hand.refreshHandLayout();
        EnergyPanel.setEnergy(99);
        if (player.energy != null) {
            player.energy.energy = 99;
            player.energy.energyMaster = Math.max(player.energy.energyMaster, 99);
        }
        return allCardsResolved && player.hand != null && player.hand.size() > 0;
    }

    private static AbstractCard getCardCopy(String cardId) {
        try {
            AbstractCard card = CardLibrary.getCopy(cardId);
            if (card == null) {
                AutoplayLog.warn("single_room CardLibrary returned null card=" + cardId, null);
                return null;
            }
            AutoplayLog.info(
                "single_room resolved card requested=" + cardId
                    + " actual=" + card.cardID
                    + " name=" + normalizeLogToken(card.name)
                    + " type=" + card.type
                    + " target=" + card.target
            );
            return card;
        } catch (Throwable t) {
            AutoplayLog.warn("single_room failed to resolve card card=" + cardId, t);
            return null;
        }
    }

    private static void ensureCardGroups(AbstractPlayer player) {
        if (player.hand == null) {
            player.hand = new CardGroup(CardGroup.CardGroupType.HAND);
        }
        if (player.drawPile == null) {
            player.drawPile = new CardGroup(CardGroup.CardGroupType.DRAW_PILE);
        }
        if (player.discardPile == null) {
            player.discardPile = new CardGroup(CardGroup.CardGroupType.DISCARD_PILE);
        }
        if (player.exhaustPile == null) {
            player.exhaustPile = new CardGroup(CardGroup.CardGroupType.EXHAUST_PILE);
        }
        if (player.limbo == null) {
            player.limbo = new CardGroup(CardGroup.CardGroupType.UNSPECIFIED);
        }
    }

    private static void handleCombat(AbstractPlayer player, AbstractRoom room) {
        MonsterGroup monsters = room.monsters;
        if (monsters == null || monsters.areMonstersBasicallyDead()) {
            return;
        }
        GameActionManager actionManager = AbstractDungeon.actionManager;
        if (actionManager == null) {
            return;
        }
        if (!actionManager.actions.isEmpty()
            || !actionManager.cardQueue.isEmpty()
            || actionManager.currentAction != null) {
            return;
        }
        if (actionManager.turnHasEnded || !actionManager.phase.equals(
            GameActionManager.Phase.WAITING_ON_USER)) {
            return;
        }
        // Bench mode: refill energy every tick so all cards remain playable.
        if (AutoplayConfig.isSingleRoomBenchModeEnabled()) {
            EnergyPanel.setEnergy(99);
            if (player.energy != null) {
                player.energy.energy = 99;
                player.energy.energyMaster = Math.max(player.energy.energyMaster, 99);
            }
        }
        AbstractCard playable = pickRandomPlayableCard(player, monsters);
        if (playable != null) {
            playCard(player, playable, monsters);
            return;
        }
        try {
            actionManager.callEndTurnEarlySequence();
            AutoplayLog.info("single_room combat ended turn (no playable card)");
        } catch (Throwable t) {
            AutoplayLog.warn("single_room combat end turn threw", t);
        }
    }

    private static AbstractCard pickRandomPlayableCard(AbstractPlayer player, MonsterGroup monsters) {
        if (player.hand == null || player.hand.group == null || player.hand.group.isEmpty()) {
            return null;
        }
        ArrayList<AbstractCard> playable = new ArrayList<>();
        for (AbstractCard card : player.hand.group) {
            if (card == null) {
                continue;
            }
            AbstractMonster sampleTarget = pickRandomAliveMonster(monsters);
            try {
                if (card.canUse(player, sampleTarget)) {
                    playable.add(card);
                }
            } catch (Throwable t) {
                AutoplayLog.debug("single_room card.canUse threw card=" + card.cardID);
            }
        }
        if (playable.isEmpty()) {
            return null;
        }
        return playable.get(RNG.nextInt(playable.size()));
    }

    private static void playCard(AbstractPlayer player, AbstractCard card, MonsterGroup monsters) {
        AbstractMonster target = null;
        if (cardNeedsSingleTarget(card)) {
            target = pickRandomAliveMonster(monsters);
            if (target == null) {
                return;
            }
        }
        try {
            player.useCard(card, target, EnergyPanel.totalCount);
            AutoplayLog.info(
                "single_room combat played card=" + card.cardID
                    + " target=" + (target == null ? "<none>" : target.id)
            );
        } catch (Throwable t) {
            AutoplayLog.warn("single_room combat useCard threw card=" + card.cardID, t);
        }
    }

    private static boolean cardNeedsSingleTarget(AbstractCard card) {
        if (card == null) {
            return false;
        }
        return card.target == AbstractCard.CardTarget.ENEMY
            || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY;
    }

    private static AbstractMonster pickRandomAliveMonster(MonsterGroup monsters) {
        if (monsters == null || monsters.monsters == null) {
            return null;
        }
        ArrayList<AbstractMonster> alive = new ArrayList<>();
        for (AbstractMonster monster : monsters.monsters) {
            if (monster == null) {
                continue;
            }
            if (monster.isDying || monster.isDead || monster.escaped || monster.halfDead) {
                continue;
            }
            if (monster.currentHealth <= 0) {
                continue;
            }
            alive.add(monster);
        }
        if (alive.isEmpty()) {
            return null;
        }
        return alive.get(RNG.nextInt(alive.size()));
    }

    private static CharacterOption findCharacterOption(CharacterSelectScreen screen, AutoplaySingleRoomSpec activeSpec) {
        if (screen == null) {
            return null;
        }
        CharacterOption visible = findCharacterOption(screen.options, activeSpec);
        if (visible != null) {
            return visible;
        }
        CharacterOption paged = pageToCharacterOption(screen, activeSpec);
        if (paged != null) {
            return paged;
        }
        return null;
    }

    private static CharacterOption findCharacterOption(ArrayList<CharacterOption> options, AutoplaySingleRoomSpec activeSpec) {
        for (CharacterOption option : options) {
            if (option == null) {
                continue;
            }
            if (activeSpec.matchesCharacter(describe(option))) {
                return option;
            }
            if (option.name != null && activeSpec.matchesCharacter(option.name)) {
                return option;
            }
        }
        return null;
    }

    private static CharacterOption pageToCharacterOption(
        CharacterSelectScreen screen,
        AutoplaySingleRoomSpec activeSpec
    ) {
        try {
            Field allOptionsField = screen.getClass().getDeclaredField("allOptions");
            allOptionsField.setAccessible(true);
            Object allOptionsValue = allOptionsField.get(screen);
            if (!(allOptionsValue instanceof ArrayList)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            ArrayList<CharacterOption> allOptions = (ArrayList<CharacterOption>)allOptionsValue;
            int optionIndex = indexOfCharacterOption(allOptions, activeSpec);
            if (optionIndex < 0) {
                return null;
            }
            int optionsPerIndex = getIntField(screen, "optionsPerIndex", 4);
            if (optionsPerIndex <= 0) {
                return null;
            }
            int targetPage = optionIndex / optionsPerIndex;
            if (!setCharacterSelectPage(screen, targetPage)) {
                return null;
            }
            AutoplayLog.info(
                "single_room paged character select targetPage=" + targetPage
                    + " option=" + describe(allOptions.get(optionIndex))
            );
            return findCharacterOption(screen.options, activeSpec);
        } catch (Throwable t) {
            AutoplayLog.warn("single_room failed to page character select", t);
            return null;
        }
    }

    private static int indexOfCharacterOption(
        ArrayList<CharacterOption> options,
        AutoplaySingleRoomSpec activeSpec
    ) {
        for (int i = 0; i < options.size(); i++) {
            CharacterOption option = options.get(i);
            if (option == null) {
                continue;
            }
            if (activeSpec.matchesCharacter(describe(option))) {
                return i;
            }
            if (option.name != null && activeSpec.matchesCharacter(option.name)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean setCharacterSelectPage(CharacterSelectScreen screen, int targetPage) {
        try {
            int currentPage = getIntField(screen, "selectIndex", 0);
            Method setCurrentOptions = screen.getClass().getDeclaredMethod("setCurrentOptions", boolean.class);
            setCurrentOptions.setAccessible(true);
            while (currentPage < targetPage) {
                setCurrentOptions.invoke(screen, Boolean.TRUE);
                currentPage++;
            }
            while (currentPage > targetPage) {
                setCurrentOptions.invoke(screen, Boolean.FALSE);
                currentPage--;
            }
            return currentPage == targetPage;
        } catch (Throwable t) {
            AutoplayLog.warn("single_room failed to switch character page", t);
            return false;
        }
    }

    private static int getIntField(Object target, String name, int fallback) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String describeCharacterOptions(CharacterSelectScreen screen) {
        if (screen == null) {
            return "<no-screen>";
        }
        ArrayList<CharacterOption> options = screen.options;
        try {
            Field allOptionsField = screen.getClass().getDeclaredField("allOptions");
            allOptionsField.setAccessible(true);
            Object allOptionsValue = allOptionsField.get(screen);
            if (allOptionsValue instanceof ArrayList) {
                @SuppressWarnings("unchecked")
                ArrayList<CharacterOption> allOptions = (ArrayList<CharacterOption>)allOptionsValue;
                options = allOptions;
            }
        } catch (Throwable ignored) {
        }
        if (options == null || options.isEmpty()) {
            return "<none>";
        }
        StringBuilder builder = new StringBuilder();
        int count = Math.min(options.size(), 16);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(describe(options.get(i)));
        }
        if (options.size() > count) {
            builder.append(",...");
        }
        return builder.toString();
    }

    private static boolean isIncompleteNativeSelection(CharacterOption option) {
        if (option == null || option.c == null || option.c.chosenClass == null) {
            return false;
        }
        return CardCrawlGame.chosenCharacter != option.c.chosenClass;
    }

    private static void queueCharacterOptionClick(CharacterOption option) {
        if (option.hb == null || option.hb.clicked) {
            return;
        }
        InputHelper.mX = Math.round(option.hb.cX);
        InputHelper.mY = Math.round(option.hb.cY);
        option.hb.clicked = true;
        AutoplayLog.info("single_room queued character click=" + describe(option));
    }

    private static void deleteStaleSavesOnce() {
        if (staleSavesDeleted) {
            return;
        }
        staleSavesDeleted = true;
        int deleted = 0;
        AbstractPlayer.PlayerClass[] classes = AbstractPlayer.PlayerClass.values();
        for (AbstractPlayer.PlayerClass playerClass : classes) {
            String savePath = SaveAndContinue.getPlayerSavePath(playerClass);
            if (deleteSaveFile(savePath)) {
                deleted++;
            }
            if (deleteSaveFile(savePath + ".backUp")) {
                deleted++;
            }
        }
        AutoplayLog.info("single_room cleared stale saves count=" + deleted);
    }

    private static boolean deleteSaveFile(String savePath) {
        if (savePath == null || savePath.length() == 0) {
            return false;
        }
        try {
            com.badlogic.gdx.files.FileHandle file = Gdx.files.local(savePath);
            return file.exists() && file.delete();
        } catch (Throwable t) {
            AutoplayLog.warn("single_room failed to delete stale save " + savePath, t);
            return false;
        }
    }

    private static void removeStaleResumeButtons(MainMenuScreen menu) {
        if (menu.buttons == null) {
            return;
        }
        boolean hasPlayButton = false;
        for (int i = menu.buttons.size() - 1; i >= 0; i--) {
            MenuButton button = menu.buttons.get(i);
            if (button == null) {
                continue;
            }
            if (button.result == MenuButton.ClickResult.PLAY) {
                hasPlayButton = true;
                continue;
            }
            if (button.result == MenuButton.ClickResult.ABANDON_RUN
                || button.result == MenuButton.ClickResult.RESUME_GAME) {
                menu.buttons.remove(i);
            }
        }
        if (!hasPlayButton) {
            menu.buttons.add(new MenuButton(MenuButton.ClickResult.PLAY, menu.buttons.size()));
        }
    }

    private static void clickProceedButtonIfVisible(String reason) {
        if (AbstractDungeon.overlayMenu == null || AbstractDungeon.overlayMenu.proceedButton == null) {
            return;
        }
        try {
            java.lang.reflect.Field field =
                AbstractDungeon.overlayMenu.proceedButton.getClass().getDeclaredField("hb");
            field.setAccessible(true);
            com.megacrit.cardcrawl.helpers.Hitbox hb =
                (com.megacrit.cardcrawl.helpers.Hitbox) field.get(AbstractDungeon.overlayMenu.proceedButton);
            if (hb != null && !hb.clicked) {
                hb.clicked = true;
                AutoplayLog.info("single_room proceed pressed reason=" + reason);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void logResult(String outcome, AbstractPlayer player, AbstractRoom room) {
        if (resultLogged) {
            return;
        }
        resultLogged = true;
        MonsterGroup monsters = room == null ? null : room.monsters;
        AutoplayLog.info(
            "single_room result outcome=" + outcome
                + " character=" + normalizeLogToken(safePlayerClass(player))
                + " monster=" + normalizeLogToken(spec == null ? "<none>" : spec.monsterId)
                + " turns=" + GameActionManager.turn
                + " playerHp=" + (player == null ? -1 : player.currentHealth)
                + " monsterHp=" + remainingMonsterHp(monsters)
        );
    }

    private static void logConfigError(String detail) {
        if (resultLogged) {
            return;
        }
        roomConfigurationFailed = true;
        resultLogged = true;
        AutoplayLog.info(
            "single_room result outcome=config_error"
                + " character=" + normalizeLogToken(spec == null ? "<none>" : spec.characterId)
                + " monster=" + normalizeLogToken(spec == null ? "<none>" : spec.monsterId)
                + " turns=" + GameActionManager.turn
                + " playerHp=-1"
                + " monsterHp=-1"
                + " detail=" + normalizeLogToken(detail)
        );
    }

    private static int remainingMonsterHp(MonsterGroup monsters) {
        if (monsters == null || monsters.monsters == null) {
            return -1;
        }
        int total = 0;
        for (AbstractMonster monster : monsters.monsters) {
            if (monster != null && !monster.isDead && !monster.isDying && !monster.escaped) {
                total += Math.max(0, monster.currentHealth);
            }
        }
        return total;
    }

    private static AbstractRoom safeGetCurrentRoom() {
        try {
            return AbstractDungeon.getCurrRoom();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String describe(CharacterOption option) {
        if (option == null) {
            return "<null>";
        }
        if (option.c != null && option.c.chosenClass != null) {
            return option.c.chosenClass.name();
        }
        return option.name == null ? "<unnamed>" : option.name;
    }

    private static String safePlayerClass(AbstractPlayer player) {
        if (player == null || player.chosenClass == null) {
            return "<none>";
        }
        return player.chosenClass.name();
    }

    private static String normalizeLogToken(String value) {
        if (value == null || value.length() == 0) {
            return "<empty>";
        }
        return value.trim().replace(' ', '_').toLowerCase(Locale.ROOT);
    }

}
