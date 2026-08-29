package io.stamethyst.compatmod.core;

import com.badlogic.gdx.Gdx;
import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import com.megacrit.cardcrawl.blights.AbstractBlight;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rooms.TreasureRoomBoss;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

public final class CompatRuntimeState {
    private static final String FIRST_PERSON_VIEW_MOD_ID = "FirstPerson";
    private static final String RUNTIME_COMPAT_DEBUG_PROP =
        "amethyst.runtime_compat.debug";
    private static final String FONT_SCALE_PROP = "amethyst.font_scale";
    private static final String UI_SCALE_PROP = "amethyst.ui_scale";
    private static final String TOUCHSCREEN_POLICY_PROP = "amethyst.touchscreen_policy";
    private static final String TOUCHSCREEN_POLICY_VANILLA_ALLOWLIST = "vanilla_allowlist";
    private static final String NATIVE_TOUCHSCREEN_ENABLED_PROP =
        "amethyst.native_touchscreen_enabled";
    private static final String TOUCH_INDICATOR_ENABLED_PROP =
        "amethyst.touch_indicator_enabled";
    private static final String TOUCHSCREEN_STATE_CLEANUP_PROP =
        "amethyst.runtime_compat.touchscreen_state_cleanup";
    private static final String TOUCHSCREEN_CARD_PLAY_OPTIMIZATION_PROP =
        "amethyst.runtime_compat.touchscreen_card_play_optimization";
    private static final String TOUCHSCREEN_CARD_GESTURE_PROP =
        "amethyst.runtime_compat.touchscreen_card_gesture";
    private static final String TOUCHSCREEN_CARD_TAP_INSPECT_PROP =
        "amethyst.runtime_compat.touchscreen_card_tap_inspect";
    private static final String TOUCHSCREEN_CARD_TAP_PLAY_PROP =
        "amethyst.runtime_compat.touchscreen_card_tap_play";
    private static final String TOUCHSCREEN_CURSOR_WARP_CLEANUP_PROP =
        "amethyst.runtime_compat.touchscreen_cursor_warp_cleanup";
    private static final String TOUCHSCREEN_TARGET_ASSIST_PROP =
        "amethyst.runtime_compat.touchscreen_target_assist";
    private static final String TOUCHSCREEN_IDLE_CARD_HOVER_CLEANUP_PROP =
        "amethyst.runtime_compat.touchscreen_idle_card_hover_cleanup";
    private static final String CAMPFIRE_DESKTOP_TOUCH_CONFIRM_PROP =
        "amethyst.runtime_compat.campfire_desktop_touch_confirm";
    private static final String HAND_LAYOUT_ROOM_CONTEXT_RESCUE_PROP =
        "amethyst.runtime_compat.rescue.hand_layout_room_context";
    private static final String ROOM_TRANSITION_RESCUE_PROP =
        "amethyst.runtime_compat.rescue.room_transition";
    private static final String EVENT_ROOM_RESCUE_PROP =
        "amethyst.runtime_compat.rescue.event_room";
    private static final String SHOP_ROOM_RESCUE_PROP =
        "amethyst.runtime_compat.rescue.shop_room";
    private static final String BASEMOD_SAVE_LOAD_RESCUE_PROP =
        "amethyst.runtime_compat.rescue.basemod_save_load";
    private static final String RELIC_ENTER_ROOM_RESCUE_PROP =
        "amethyst.runtime_compat.rescue.relic_enter_room";
    private static final String DUNGEON_RENDER_ROOM_CONTEXT_RESCUE_PROP =
        "amethyst.runtime_compat.rescue.dungeon_render_room_context";
    private static final String POWER_ICON_RENDER_RESCUE_PROP =
        "amethyst.runtime_compat.rescue.power_icon_render";
    private static final String BASEMOD_CUSTOM_MONSTER_RENDER_RESCUE_PROP =
        "amethyst.runtime_compat.rescue.basemod_custom_monster_render";
    private static final String NON_COMBAT_PLAYER_RENDER_RESCUE_PROP =
        "amethyst.runtime_compat.rescue.non_combat_player_render";
    private static final String CARD_TOOLTIP_KEYWORD_RESCUE_PROP =
        "amethyst.runtime_compat.rescue.card_tooltip_keyword";
    private static final float DEFAULT_TEXT_SCALE = 1.0f;
    private static final float BIG_TEXT_SCALE = 1.2f;
    private static final float DEFAULT_UI_SCALE = 1.0f;
    private static final float UI_SCALE_EPSILON = 0.0001f;
    private static final boolean RUNTIME_COMPAT_DEBUG_ENABLED =
        readBooleanSystemProperty(RUNTIME_COMPAT_DEBUG_PROP, false);
    private static final String TOUCHSCREEN_POLICY =
        readStringSystemProperty(TOUCHSCREEN_POLICY_PROP, "global");
    private static final boolean VANILLA_TOUCHSCREEN_ALLOWLIST_ACTIVE =
        TOUCHSCREEN_POLICY_VANILLA_ALLOWLIST.equalsIgnoreCase(TOUCHSCREEN_POLICY);
    private static final boolean NATIVE_TOUCHSCREEN_ENABLED =
        readBooleanSystemProperty(NATIVE_TOUCHSCREEN_ENABLED_PROP, false);
    private static final boolean TOUCH_INDICATOR_ENABLED =
        readBooleanSystemProperty(TOUCH_INDICATOR_ENABLED_PROP, NATIVE_TOUCHSCREEN_ENABLED);
    private static final boolean TOUCHSCREEN_STATE_CLEANUP_ENABLED =
        readBooleanSystemProperty(TOUCHSCREEN_STATE_CLEANUP_PROP, true);
    private static final boolean TOUCHSCREEN_CARD_PLAY_OPTIMIZATION_ENABLED =
        readBooleanSystemProperty(TOUCHSCREEN_CARD_PLAY_OPTIMIZATION_PROP, true);
    private static final boolean TOUCHSCREEN_CARD_GESTURE_ENABLED =
        readBooleanSystemProperty(TOUCHSCREEN_CARD_GESTURE_PROP, true);
    private static final boolean TOUCHSCREEN_CARD_TAP_INSPECT_ENABLED =
        readBooleanSystemProperty(TOUCHSCREEN_CARD_TAP_INSPECT_PROP, false);
    private static final boolean TOUCHSCREEN_CARD_TAP_PLAY_ENABLED =
        readBooleanSystemProperty(TOUCHSCREEN_CARD_TAP_PLAY_PROP, false);
    private static final boolean TOUCHSCREEN_CURSOR_WARP_CLEANUP_ENABLED =
        readBooleanSystemProperty(TOUCHSCREEN_CURSOR_WARP_CLEANUP_PROP, true);
    private static final boolean TOUCHSCREEN_TARGET_ASSIST_ENABLED =
        readBooleanSystemProperty(TOUCHSCREEN_TARGET_ASSIST_PROP, true);
    private static final boolean TOUCHSCREEN_IDLE_CARD_HOVER_CLEANUP_ENABLED =
        readBooleanSystemProperty(TOUCHSCREEN_IDLE_CARD_HOVER_CLEANUP_PROP, true);
    private static final boolean CAMPFIRE_DESKTOP_TOUCH_CONFIRM_ENABLED =
        readBooleanSystemProperty(CAMPFIRE_DESKTOP_TOUCH_CONFIRM_PROP, true);
    private static final boolean HAND_LAYOUT_ROOM_CONTEXT_RESCUE_ENABLED =
        readBooleanSystemProperty(HAND_LAYOUT_ROOM_CONTEXT_RESCUE_PROP, true);
    private static final boolean ROOM_TRANSITION_RESCUE_ENABLED =
        readBooleanSystemProperty(ROOM_TRANSITION_RESCUE_PROP, true);
    private static final boolean EVENT_ROOM_RESCUE_ENABLED =
        readBooleanSystemProperty(EVENT_ROOM_RESCUE_PROP, true);
    private static final boolean SHOP_ROOM_RESCUE_ENABLED =
        readBooleanSystemProperty(SHOP_ROOM_RESCUE_PROP, true);
    private static final boolean BASEMOD_SAVE_LOAD_RESCUE_ENABLED =
        readBooleanSystemProperty(BASEMOD_SAVE_LOAD_RESCUE_PROP, true);
    private static final boolean RELIC_ENTER_ROOM_RESCUE_ENABLED =
        readBooleanSystemProperty(RELIC_ENTER_ROOM_RESCUE_PROP, true);
    private static final boolean DUNGEON_RENDER_ROOM_CONTEXT_RESCUE_ENABLED =
        readBooleanSystemProperty(DUNGEON_RENDER_ROOM_CONTEXT_RESCUE_PROP, true);
    private static final boolean POWER_ICON_RENDER_RESCUE_ENABLED =
        readBooleanSystemProperty(POWER_ICON_RENDER_RESCUE_PROP, true);
    private static final boolean BASEMOD_CUSTOM_MONSTER_RENDER_RESCUE_ENABLED =
        readBooleanSystemProperty(BASEMOD_CUSTOM_MONSTER_RENDER_RESCUE_PROP, true);
    private static final boolean NON_COMBAT_PLAYER_RENDER_RESCUE_ENABLED =
        readBooleanSystemProperty(NON_COMBAT_PLAYER_RENDER_RESCUE_PROP, true);
    private static final boolean CARD_TOOLTIP_KEYWORD_RESCUE_ENABLED =
        readBooleanSystemProperty(CARD_TOOLTIP_KEYWORD_RESCUE_PROP, true);
    private static final float CONFIGURED_FONT_SCALE =
        readFloatSystemProperty(FONT_SCALE_PROP, Float.NaN);
    private static final float CONFIGURED_UI_SCALE =
        readFloatSystemProperty(UI_SCALE_PROP, Float.NaN);
    private static final GuardedReflectionAccess UNSUPPORTED_GUARDED_ACCESS =
        new GuardedReflectionAccess(null, null, null, null);
    private static final Map<Class<?>, GuardedReflectionAccess> GUARDED_ACCESS_BY_CLASS =
        new WeakHashMap<Class<?>, GuardedReflectionAccess>();
    private static final FieldAccess UNSUPPORTED_FIELD_ACCESS = new FieldAccess(null);
    private static final Map<Class<?>, FieldAccess> BASE_TRIBUTES_ACCESS_BY_CLASS =
        new WeakHashMap<Class<?>, FieldAccess>();
    private static final Map<Class<?>, FieldAccess> BASE_SUMMONS_ACCESS_BY_CLASS =
        new WeakHashMap<Class<?>, FieldAccess>();
    private static final Map<AbstractCard, GuardedDynamicSnapshot> GUARDED_DYNAMIC_SNAPSHOTS =
        new WeakHashMap<AbstractCard, GuardedDynamicSnapshot>();
    private static boolean startupConfigurationLogged;
    private static boolean guardedDynamicCacheLogged;
    private static boolean guardedDynamicCacheFailureLogged;
    private static boolean duelistBaseValueShortcutLogged;
    private static boolean duelistBaseValueShortcutFailureLogged;

    private CompatRuntimeState() {
    }

    public static void logStartupConfiguration() {
        synchronized (CompatRuntimeState.class) {
            if (startupConfigurationLogged) {
                return;
            }
            startupConfigurationLogged = true;
            System.out.println(
                "[amethyst-runtime-compat] init version=1.0.38 guardedDynamicCache=true "
                    + "duelistBaseValueShortcuts=true "
                    + "fontScale="
                    + (hasConfiguredFontScale()
                    ? Float.toString(CONFIGURED_FONT_SCALE)
                    : "<default>")
                    + " uiScale="
                    + Float.toString(getConfiguredUiScale())
                    + " mobileUiLayout="
                    + Boolean.toString(isMobileUiScaleStrategyActive())
                    + " touchscreenPolicy="
                    + TOUCHSCREEN_POLICY
                    + " nativeTouchscreen="
                    + Boolean.toString(NATIVE_TOUCHSCREEN_ENABLED)
                    + " touchIndicator="
                    + Boolean.toString(TOUCH_INDICATOR_ENABLED)
                    + " touchStateCleanup="
                    + Boolean.toString(TOUCHSCREEN_STATE_CLEANUP_ENABLED)
                    + " touchCardPlayOptimization="
                    + Boolean.toString(TOUCHSCREEN_CARD_PLAY_OPTIMIZATION_ENABLED)
                    + " touchCardGesture="
                    + Boolean.toString(TOUCHSCREEN_CARD_GESTURE_ENABLED)
                    + " touchCardTapInspect="
                    + Boolean.toString(TOUCHSCREEN_CARD_TAP_INSPECT_ENABLED)
                    + " touchCardTapPlay="
                    + Boolean.toString(TOUCHSCREEN_CARD_TAP_PLAY_ENABLED)
                    + " touchCursorWarpCleanup="
                    + Boolean.toString(TOUCHSCREEN_CURSOR_WARP_CLEANUP_ENABLED)
                    + " touchTargetAssist="
                    + Boolean.toString(TOUCHSCREEN_TARGET_ASSIST_ENABLED)
                    + " touchIdleCardHoverCleanup="
                    + Boolean.toString(TOUCHSCREEN_IDLE_CARD_HOVER_CLEANUP_ENABLED)
                    + " campfireDesktopTouchConfirm="
                    + Boolean.toString(CAMPFIRE_DESKTOP_TOUCH_CONFIRM_ENABLED)
                    + " rescueHandLayoutRoom="
                    + Boolean.toString(HAND_LAYOUT_ROOM_CONTEXT_RESCUE_ENABLED)
                    + " rescueRoomTransition="
                    + Boolean.toString(ROOM_TRANSITION_RESCUE_ENABLED)
                    + " rescueEventRoom="
                    + Boolean.toString(EVENT_ROOM_RESCUE_ENABLED)
                    + " rescueShopRoom="
                    + Boolean.toString(SHOP_ROOM_RESCUE_ENABLED)
                    + " rescueBaseModSaveLoad="
                    + Boolean.toString(BASEMOD_SAVE_LOAD_RESCUE_ENABLED)
                    + " rescueRelicEnterRoom="
                    + Boolean.toString(RELIC_ENTER_ROOM_RESCUE_ENABLED)
                    + " rescueDungeonRenderRoom="
                    + Boolean.toString(DUNGEON_RENDER_ROOM_CONTEXT_RESCUE_ENABLED)
                    + " rescuePowerIconRender="
                    + Boolean.toString(POWER_ICON_RENDER_RESCUE_ENABLED)
                    + " rescueBaseModCustomMonsterRender="
                    + Boolean.toString(BASEMOD_CUSTOM_MONSTER_RENDER_RESCUE_ENABLED)
                    + " rescueNonCombatPlayerRender="
                    + Boolean.toString(NON_COMBAT_PLAYER_RENDER_RESCUE_ENABLED)
                    + " rescueCardTooltipKeyword="
                    + Boolean.toString(CARD_TOOLTIP_KEYWORD_RESCUE_ENABLED)
            );
            System.out.println(
                "[amethyst-runtime-compat] guarded dynamic cache active: "
                    + "duelist:G render lookups reuse a frame-local snapshot"
            );
            System.out.println(
                "[amethyst-runtime-compat] duelist base-value shortcuts active: "
                    + "duelist:TRIB and duelist:SUMM reuse current base fields instead of makeCopy+upgrade"
            );
            if (RUNTIME_COMPAT_DEBUG_ENABLED) {
                System.out.println(
                    "[amethyst-runtime-compat] debug property enabled: "
                        + RUNTIME_COMPAT_DEBUG_PROP
                );
            }
        }
    }

    public static GuardedDynamicSnapshot getGuardedDynamicSnapshot(AbstractCard card, String source) {
        if (card == null) {
            return null;
        }
        GuardedReflectionAccess access = getGuardedReflectionAccess(card.getClass());
        if (access == UNSUPPORTED_GUARDED_ACCESS) {
            return null;
        }

        long frameId = getCurrentFrameId();
        if (frameId >= 0L) {
            synchronized (CompatRuntimeState.class) {
                GuardedDynamicSnapshot cached = GUARDED_DYNAMIC_SNAPSHOTS.get(card);
                if (cached != null && cached.frameId == frameId) {
                    return cached;
                }
            }
        }

        GuardedDynamicSnapshot resolved;
        try {
            resolved = resolveGuardedDynamicSnapshot(card, access, frameId);
        } catch (RuntimeException e) {
            logGuardedDynamicCacheFailureOnce(card, source, e);
            return null;
        }
        if (resolved == null) {
            return null;
        }

        if (frameId >= 0L) {
            synchronized (CompatRuntimeState.class) {
                GUARDED_DYNAMIC_SNAPSHOTS.put(card, resolved);
            }
        }
        logGuardedDynamicCacheOnce(card, source, resolved);
        return resolved;
    }

    public static float remapPrepFontSize(float requestedSize, boolean bigTextMode) {
        if (!hasConfiguredFontScale()) {
            return requestedSize;
        }
        float baselineScale = bigTextMode ? BIG_TEXT_SCALE : DEFAULT_TEXT_SCALE;
        if (baselineScale <= 0.0f) {
            return requestedSize;
        }
        return requestedSize * (CONFIGURED_FONT_SCALE / baselineScale);
    }

    public static boolean isMobileUiScaleStrategyActive() {
        return getConfiguredUiScale() > (DEFAULT_UI_SCALE + UI_SCALE_EPSILON);
    }

    public static boolean resolveMobileLayoutFlag(boolean originalValue) {
        return originalValue || isMobileUiScaleStrategyActive();
    }

    public static boolean isTouchscreenStateCleanupEnabled() {
        return TOUCHSCREEN_STATE_CLEANUP_ENABLED;
    }

    public static boolean isTouchscreenCardPlayOptimizationEnabled() {
        return TOUCHSCREEN_CARD_PLAY_OPTIMIZATION_ENABLED;
    }

    public static boolean isFirstPersonViewEnabled() {
        if (Loader.MODINFOS == null) {
            return false;
        }
        for (ModInfo modInfo : Loader.MODINFOS) {
            if (modInfo != null && FIRST_PERSON_VIEW_MOD_ID.equalsIgnoreCase(modInfo.ID)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTouchscreenCardGestureEnabled() {
        return TOUCHSCREEN_CARD_PLAY_OPTIMIZATION_ENABLED && TOUCHSCREEN_CARD_GESTURE_ENABLED;
    }

    public static boolean isTouchscreenCardTapInspectEnabled() {
        return TOUCHSCREEN_CARD_PLAY_OPTIMIZATION_ENABLED && TOUCHSCREEN_CARD_TAP_INSPECT_ENABLED;
    }

    public static boolean isTouchscreenCardTapPlayEnabled() {
        return TOUCHSCREEN_CARD_PLAY_OPTIMIZATION_ENABLED && TOUCHSCREEN_CARD_TAP_PLAY_ENABLED;
    }

    public static boolean isTouchscreenCursorWarpCleanupEnabled() {
        return TOUCHSCREEN_CARD_PLAY_OPTIMIZATION_ENABLED && TOUCHSCREEN_CURSOR_WARP_CLEANUP_ENABLED;
    }

    public static boolean isTouchscreenTargetAssistEnabled() {
        return TOUCHSCREEN_CARD_PLAY_OPTIMIZATION_ENABLED && TOUCHSCREEN_TARGET_ASSIST_ENABLED;
    }

    public static boolean isTouchscreenIdleCardHoverCleanupEnabled() {
        return TOUCHSCREEN_CARD_PLAY_OPTIMIZATION_ENABLED && TOUCHSCREEN_IDLE_CARD_HOVER_CLEANUP_ENABLED;
    }

    public static boolean isCampfireDesktopTouchConfirmEnabled() {
        return CAMPFIRE_DESKTOP_TOUCH_CONFIRM_ENABLED;
    }

    public static boolean isHandLayoutRoomContextRescueEnabled() {
        return HAND_LAYOUT_ROOM_CONTEXT_RESCUE_ENABLED;
    }

    public static boolean isRoomTransitionRescueEnabled() {
        return ROOM_TRANSITION_RESCUE_ENABLED;
    }

    public static boolean isEventRoomRescueEnabled() {
        return EVENT_ROOM_RESCUE_ENABLED;
    }

    public static boolean isShopRoomRescueEnabled() {
        return SHOP_ROOM_RESCUE_ENABLED;
    }

    public static boolean isBaseModSaveLoadRescueEnabled() {
        return BASEMOD_SAVE_LOAD_RESCUE_ENABLED;
    }

    public static boolean isRelicEnterRoomRescueEnabled() {
        return RELIC_ENTER_ROOM_RESCUE_ENABLED;
    }

    public static boolean isDungeonRenderRoomContextRescueEnabled() {
        return DUNGEON_RENDER_ROOM_CONTEXT_RESCUE_ENABLED;
    }

    public static boolean isPowerIconRenderRescueEnabled() {
        return POWER_ICON_RENDER_RESCUE_ENABLED;
    }

    public static boolean isBaseModCustomMonsterRenderRescueEnabled() {
        return BASEMOD_CUSTOM_MONSTER_RENDER_RESCUE_ENABLED;
    }

    public static boolean isNonCombatPlayerRenderRescueEnabled() {
        return NON_COMBAT_PLAYER_RENDER_RESCUE_ENABLED;
    }

    public static boolean isCardTooltipKeywordRescueEnabled() {
        return CARD_TOOLTIP_KEYWORD_RESCUE_ENABLED;
    }

    public static boolean resolveTouchIndicatorFlag(boolean originalValue) {
        return originalValue || TOUCH_INDICATOR_ENABLED;
    }

    public static boolean shouldSuppressTouchIndicatorRender() {
        return NATIVE_TOUCHSCREEN_ENABLED && !TOUCH_INDICATOR_ENABLED;
    }

    public static boolean resolveMainMenuTouchLayoutTouchscreenFlag(boolean originalValue) {
        if (originalValue) {
            return true;
        }
        return isVanillaTouchscreenAllowlistActive() && NATIVE_TOUCHSCREEN_ENABLED;
    }

    public static boolean resolveMainMenuTouchLayoutMobileFlag(boolean originalValue) {
        if (originalValue) {
            return true;
        }
        return isVanillaTouchscreenAllowlistActive() && NATIVE_TOUCHSCREEN_ENABLED;
    }

    public static boolean resolveVanillaAllowlistedTouchscreenFlag(boolean originalValue) {
        if (!isVanillaTouchscreenAllowlistActive()) {
            return originalValue;
        }
        return NATIVE_TOUCHSCREEN_ENABLED;
    }

    public static boolean resolveVanillaShopTouchscreenFlag(boolean originalValue) {
        return resolveVanillaAllowlistedTouchscreenFlag(originalValue);
    }

    public static boolean resolveRelicTouchscreenForObtain(
        boolean originalValue,
        AbstractRelic relic
    ) {
        if (!isVanillaTouchscreenAllowlistActive()) {
            return originalValue;
        }
        return NATIVE_TOUCHSCREEN_ENABLED && isVanillaBossRelicScreenRelic(relic);
    }

    public static boolean resolveBlightTouchscreenForObtain(
        boolean originalValue,
        AbstractBlight blight
    ) {
        if (!isVanillaTouchscreenAllowlistActive()) {
            return originalValue;
        }
        return NATIVE_TOUCHSCREEN_ENABLED && isVanillaBossRelicScreenBlight(blight);
    }

    private static boolean isVanillaTouchscreenAllowlistActive() {
        return VANILLA_TOUCHSCREEN_ALLOWLIST_ACTIVE;
    }

    private static boolean isVanillaBossRelicScreenRelic(AbstractRelic relic) {
        if (relic == null) {
            return false;
        }
        try {
            return AbstractDungeon.screen == AbstractDungeon.CurrentScreen.BOSS_REWARD
                && AbstractDungeon.getCurrRoom() instanceof TreasureRoomBoss
                && AbstractDungeon.bossRelicScreen != null
                && AbstractDungeon.bossRelicScreen.relics != null
                && AbstractDungeon.bossRelicScreen.relics.contains(relic);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isVanillaBossRelicScreenBlight(AbstractBlight blight) {
        if (blight == null) {
            return false;
        }
        try {
            return AbstractDungeon.screen == AbstractDungeon.CurrentScreen.BOSS_REWARD
                && AbstractDungeon.getCurrRoom() instanceof TreasureRoomBoss
                && AbstractDungeon.bossRelicScreen != null
                && AbstractDungeon.bossRelicScreen.blights != null
                && AbstractDungeon.bossRelicScreen.blights.contains(blight);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static Integer getDuelistBaseTributes(AbstractCard card) {
        return getDuelistBaseValue(
            card,
            BASE_TRIBUTES_ACCESS_BY_CLASS,
            "baseTributes",
            "TributeMagicNumber.baseValue"
        );
    }

    public static Integer getDuelistBaseSummons(AbstractCard card) {
        return getDuelistBaseValue(
            card,
            BASE_SUMMONS_ACCESS_BY_CLASS,
            "baseSummons",
            "SummonMagicNumber.baseValue"
        );
    }

    private static Integer getDuelistBaseValue(
        AbstractCard card,
        Map<Class<?>, FieldAccess> accessByClass,
        String fieldName,
        String source
    ) {
        if (card == null) {
            return null;
        }
        FieldAccess access = getFieldAccess(card.getClass(), accessByClass, fieldName);
        if (access == UNSUPPORTED_FIELD_ACCESS) {
            return null;
        }
        try {
            int value = access.field.getInt(card);
            logDuelistBaseValueShortcutOnce(card, source, fieldName, value);
            return Integer.valueOf(value);
        } catch (IllegalAccessException e) {
            logDuelistBaseValueShortcutFailureOnce(card, source, fieldName, e);
            return null;
        } catch (RuntimeException e) {
            logDuelistBaseValueShortcutFailureOnce(card, source, fieldName, e);
            return null;
        }
    }

    private static GuardedDynamicSnapshot resolveGuardedDynamicSnapshot(
        AbstractCard card,
        GuardedReflectionAccess access,
        long frameId
    ) {
        try {
            int baseValue = invokeInt(access.getBaseGuardedCheck, card);
            int currentValue = invokeInt(access.getGuardedCheck, card);
            boolean modifiedForTurn = invokeBoolean(access.isGuardedCheckModifiedForTurn, card);
            int effectiveValue = invokeEffectiveGuardedRequirement(
                access.getEffectiveGuardedRequirement,
                card,
                currentValue
            );
            return new GuardedDynamicSnapshot(
                frameId,
                baseValue,
                effectiveValue,
                effectiveValue != baseValue || modifiedForTurn
            );
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to access guarded dynamic state for " + describeCard(card), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Failed to resolve guarded dynamic state for " + describeCard(card), cause);
        }
    }

    private static GuardedReflectionAccess getGuardedReflectionAccess(Class<?> cardClass) {
        synchronized (CompatRuntimeState.class) {
            GuardedReflectionAccess cached = GUARDED_ACCESS_BY_CLASS.get(cardClass);
            if (cached != null) {
                return cached;
            }
        }

        GuardedReflectionAccess resolved = resolveGuardedReflectionAccess(cardClass);
        synchronized (CompatRuntimeState.class) {
            GUARDED_ACCESS_BY_CLASS.put(cardClass, resolved);
        }
        return resolved;
    }

    private static GuardedReflectionAccess resolveGuardedReflectionAccess(Class<?> cardClass) {
        Method getEffectiveGuardedRequirement = findMethod(cardClass, "getEffectiveGuardedRequirement", 2);
        Method getBaseGuardedCheck = findMethod(cardClass, "getBaseGuardedCheck", 0);
        Method getGuardedCheck = findMethod(cardClass, "getGuardedCheck", 0);
        Method isGuardedCheckModifiedForTurn = findMethod(cardClass, "isGuardedCheckModifiedForTurn", 0);
        if (getEffectiveGuardedRequirement == null
            || getBaseGuardedCheck == null
            || getGuardedCheck == null
            || isGuardedCheckModifiedForTurn == null) {
            return UNSUPPORTED_GUARDED_ACCESS;
        }
        return new GuardedReflectionAccess(
            getEffectiveGuardedRequirement,
            getBaseGuardedCheck,
            getGuardedCheck,
            isGuardedCheckModifiedForTurn
        );
    }

    private static Method findMethod(Class<?> cardClass, String name, int parameterCount) {
        Method[] methods = cardClass.getMethods();
        for (Method method : methods) {
            if (method.getName().equals(name) && method.getParameterTypes().length == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private static int invokeEffectiveGuardedRequirement(Method method, AbstractCard card, int currentValue)
        throws IllegalAccessException, InvocationTargetException {
        Object result = method.invoke(card, card, Integer.valueOf(currentValue));
        return result instanceof Integer ? ((Integer) result).intValue() : -1;
    }

    private static int invokeInt(Method method, Object owner)
        throws IllegalAccessException, InvocationTargetException {
        Object result = method.invoke(owner);
        return result instanceof Integer ? ((Integer) result).intValue() : -1;
    }

    private static boolean invokeBoolean(Method method, Object owner)
        throws IllegalAccessException, InvocationTargetException {
        Object result = method.invoke(owner);
        return result instanceof Boolean && ((Boolean) result).booleanValue();
    }

    private static long getCurrentFrameId() {
        try {
            if (Gdx.graphics == null) {
                return -1L;
            }
            return Gdx.graphics.getFrameId();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static void logGuardedDynamicCacheOnce(
        AbstractCard card,
        String source,
        GuardedDynamicSnapshot snapshot
    ) {
        if (!RUNTIME_COMPAT_DEBUG_ENABLED) {
            return;
        }
        synchronized (CompatRuntimeState.class) {
            if (guardedDynamicCacheLogged) {
                return;
            }
            guardedDynamicCacheLogged = true;
            System.out.println(
                "[amethyst-runtime-compat] guarded dynamic cache engaged path="
                    + source
                    + " card="
                    + describeCard(card)
                    + " base="
                    + snapshot.baseValue
                    + " value="
                    + snapshot.value
                    + " modified="
                    + snapshot.modified
            );
        }
    }

    private static void logGuardedDynamicCacheFailureOnce(
        AbstractCard card,
        String source,
        RuntimeException error
    ) {
        synchronized (CompatRuntimeState.class) {
            if (guardedDynamicCacheFailureLogged) {
                return;
            }
            guardedDynamicCacheFailureLogged = true;
            System.out.println(
                "[amethyst-runtime-compat] guarded dynamic cache fallback path="
                    + source
                    + " card="
                    + describeCard(card)
                    + " reason="
                    + error.getClass().getSimpleName()
                    + ": "
                    + error.getMessage()
            );
        }
    }

    private static FieldAccess getFieldAccess(
        Class<?> cardClass,
        Map<Class<?>, FieldAccess> accessByClass,
        String fieldName
    ) {
        synchronized (CompatRuntimeState.class) {
            FieldAccess cached = accessByClass.get(cardClass);
            if (cached != null) {
                return cached;
            }
        }
        FieldAccess resolved = resolveFieldAccess(cardClass, fieldName);
        synchronized (CompatRuntimeState.class) {
            accessByClass.put(cardClass, resolved);
        }
        return resolved;
    }

    private static FieldAccess resolveFieldAccess(Class<?> cardClass, String fieldName) {
        Field field = findField(cardClass, fieldName);
        if (field == null) {
            return UNSUPPORTED_FIELD_ACCESS;
        }
        field.setAccessible(true);
        return new FieldAccess(field);
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static void logDuelistBaseValueShortcutOnce(
        AbstractCard card,
        String source,
        String fieldName,
        int value
    ) {
        if (!RUNTIME_COMPAT_DEBUG_ENABLED) {
            return;
        }
        synchronized (CompatRuntimeState.class) {
            if (duelistBaseValueShortcutLogged) {
                return;
            }
            duelistBaseValueShortcutLogged = true;
            System.out.println(
                "[amethyst-runtime-compat] duelist base-value shortcut engaged path="
                    + source
                    + " card="
                    + describeCard(card)
                    + " field="
                    + fieldName
                    + " value="
                    + value
            );
        }
    }

    private static void logDuelistBaseValueShortcutFailureOnce(
        AbstractCard card,
        String source,
        String fieldName,
        Throwable error
    ) {
        synchronized (CompatRuntimeState.class) {
            if (duelistBaseValueShortcutFailureLogged) {
                return;
            }
            duelistBaseValueShortcutFailureLogged = true;
            System.out.println(
                "[amethyst-runtime-compat] duelist base-value shortcut fallback path="
                    + source
                    + " card="
                    + describeCard(card)
                    + " field="
                    + fieldName
                    + " reason="
                    + error.getClass().getSimpleName()
                    + ": "
                    + error.getMessage()
            );
        }
    }

    private static String describeCard(AbstractCard card) {
        if (card == null) {
            return "<null>";
        }
        if (card.cardID != null) {
            return card.cardID;
        }
        if (card.name != null) {
            return card.name;
        }
        return card.getClass().getName();
    }

    private static boolean readBooleanSystemProperty(String key, boolean defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        if ("false".equalsIgnoreCase(configured) || "0".equals(configured) || "off".equalsIgnoreCase(configured)) {
            return false;
        }
        if ("true".equalsIgnoreCase(configured) || "1".equals(configured) || "on".equalsIgnoreCase(configured)) {
            return true;
        }
        return defaultValue;
    }

    private static String readStringSystemProperty(String key, String defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        return configured;
    }

    private static boolean hasConfiguredFontScale() {
        return !Float.isNaN(CONFIGURED_FONT_SCALE) && CONFIGURED_FONT_SCALE > 0.0f;
    }

    private static float getConfiguredUiScale() {
        if (Float.isNaN(CONFIGURED_UI_SCALE) || CONFIGURED_UI_SCALE <= 0.0f) {
            return DEFAULT_UI_SCALE;
        }
        return CONFIGURED_UI_SCALE;
    }

    private static float readFloatSystemProperty(String key, float defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(configured);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static final class GuardedReflectionAccess {
        private final Method getEffectiveGuardedRequirement;
        private final Method getBaseGuardedCheck;
        private final Method getGuardedCheck;
        private final Method isGuardedCheckModifiedForTurn;

        private GuardedReflectionAccess(
            Method getEffectiveGuardedRequirement,
            Method getBaseGuardedCheck,
            Method getGuardedCheck,
            Method isGuardedCheckModifiedForTurn
        ) {
            this.getEffectiveGuardedRequirement = getEffectiveGuardedRequirement;
            this.getBaseGuardedCheck = getBaseGuardedCheck;
            this.getGuardedCheck = getGuardedCheck;
            this.isGuardedCheckModifiedForTurn = isGuardedCheckModifiedForTurn;
        }
    }

    public static final class GuardedDynamicSnapshot {
        public final long frameId;
        public final int baseValue;
        public final int value;
        public final boolean modified;

        private GuardedDynamicSnapshot(
            long frameId,
            int baseValue,
            int value,
            boolean modified
        ) {
            this.frameId = frameId;
            this.baseValue = baseValue;
            this.value = value;
            this.modified = modified;
        }
    }

    private static final class FieldAccess {
        private final Field field;

        private FieldAccess(Field field) {
            this.field = field;
        }
    }
}
