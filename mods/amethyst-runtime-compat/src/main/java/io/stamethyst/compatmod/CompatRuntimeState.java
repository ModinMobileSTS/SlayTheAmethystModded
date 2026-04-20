package io.stamethyst.compatmod;

import com.badlogic.gdx.Gdx;
import com.megacrit.cardcrawl.cards.AbstractCard;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

public final class CompatRuntimeState {
    private static final String RUNTIME_COMPAT_DEBUG_PROP =
        "amethyst.runtime_compat.debug";
    private static final String FONT_SCALE_PROP = "amethyst.font_scale";
    private static final String UI_SCALE_PROP = "amethyst.ui_scale";
    private static final float DEFAULT_TEXT_SCALE = 1.0f;
    private static final float BIG_TEXT_SCALE = 1.2f;
    private static final float DEFAULT_UI_SCALE = 1.0f;
    private static final float UI_SCALE_EPSILON = 0.0001f;
    private static final boolean RUNTIME_COMPAT_DEBUG_ENABLED =
        readBooleanSystemProperty(RUNTIME_COMPAT_DEBUG_PROP, false);
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
                "[amethyst-runtime-compat] init version=1.0.18 guardedDynamicCache=true "
                    + "duelistBaseValueShortcuts=true "
                    + "fontScale="
                    + (hasConfiguredFontScale()
                    ? Float.toString(CONFIGURED_FONT_SCALE)
                    : "<default>")
                    + " uiScale="
                    + Float.toString(getConfiguredUiScale())
                    + " mobileUiLayout="
                    + Boolean.toString(isMobileUiScaleStrategyActive())
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

    static final class GuardedDynamicSnapshot {
        final long frameId;
        final int baseValue;
        final int value;
        final boolean modified;

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
