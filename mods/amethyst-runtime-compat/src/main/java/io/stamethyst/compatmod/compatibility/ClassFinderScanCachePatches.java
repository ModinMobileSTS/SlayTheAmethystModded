package io.stamethyst.compatmod.compatibility;

import basemod.BaseMod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import io.stamethyst.compatmod.core.StartupCacheRuntimeConfig;

import org.clapper.util.classutil.ClassFilter;
import org.clapper.util.classutil.ClassFinder;
import org.clapper.util.classutil.ClassInfo;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.File;
import java.lang.reflect.Field;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClassFinderScanCachePatches {
    private static final String ENABLED_PROP = "amethyst.runtime_compat.class_finder_scan_cache";
    private static final String PROFILE_PROP = "amethyst.runtime_compat.class_finder_scan_cache_profile";
    private static final Map<String, CacheEntry> CACHE = new HashMap<String, CacheEntry>();
    private static Field placesToSearchField;
    private static Field foundClassesField;
    private static boolean reflectionUnavailable;
    private static int editCardsDepth;
    private static int editCardsHits;
    private static int editCardsMisses;
    private static int editCardsReturnedClasses;

    private ClassFinderScanCachePatches() {
    }

    @SpirePatch2(
        clz = BaseMod.class,
        method = "publishEditCards",
        requiredModId = "basemod",
        optional = true
    )
    public static class BaseModPublishEditCardsPatch {
        @SpirePrefixPatch
        public static void Prefix() {
            if (!isEnabled()) {
                return;
            }
            synchronized (ClassFinderScanCachePatches.class) {
                editCardsDepth++;
                editCardsHits = 0;
                editCardsMisses = 0;
                editCardsReturnedClasses = 0;
            }
        }

        @SpirePostfixPatch
        public static void Postfix() {
            if (!isEnabled()) {
                return;
            }
            int hits;
            int misses;
            int returnedClasses;
            int entries;
            synchronized (ClassFinderScanCachePatches.class) {
                if (editCardsDepth > 0) {
                    editCardsDepth--;
                }
                hits = editCardsHits;
                misses = editCardsMisses;
                returnedClasses = editCardsReturnedClasses;
                entries = CACHE.size();
            }
            if (hits > 0 || misses > 0) {
                System.out.println(
                    "[amethyst-runtime-compat] ClassFinder scan cache editCards hits="
                        + hits
                        + " misses="
                        + misses
                        + " returnedClasses="
                        + returnedClasses
                        + " entries="
                        + entries
                );
            }
        }
    }

    @SpirePatch2(
        cls = "expansioncontent.expansionContentMod",
        method = "autoAddCards",
        optional = true
    )
    public static class ExpansionContentAutoAddCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentAutoAddCardsCalls("expansioncontent.expansionContentMod");
        }
    }

    @SpirePatch2(
        cls = "sneckomod.SneckoMod",
        method = "autoAddCards",
        optional = true
    )
    public static class SneckoAutoAddCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentAutoAddCardsCalls("sneckomod.SneckoMod");
        }
    }

    @SpirePatch2(
        cls = "theHexaghost.HexaMod",
        method = "autoAddCards",
        optional = true
    )
    public static class HexaAutoAddCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentAutoAddCardsCalls("theHexaghost.HexaMod");
        }
    }

    @SpirePatch2(
        cls = "collector.CollectorMod",
        method = "autoAddCards",
        optional = true
    )
    public static class CollectorAutoAddCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentAutoAddCardsCalls("collector.CollectorMod");
        }
    }

    @SpirePatch2(
        cls = "automaton.AutomatonMod",
        method = "autoAddCards",
        optional = true
    )
    public static class AutomatonAutoAddCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentAutoAddCardsCalls("automaton.AutomatonMod");
        }
    }

    @SpirePatch2(
        cls = "awakenedOne.AwakenedOneMod",
        method = "autoAddCards",
        optional = true
    )
    public static class AwakenedOneAutoAddCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentAutoAddCardsCalls("awakenedOne.AwakenedOneMod");
        }
    }

    @SpirePatch2(
        cls = "champ.ChampMod",
        method = "autoAddCards",
        optional = true
    )
    public static class ChampAutoAddCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentAutoAddCardsCalls("champ.ChampMod");
        }
    }

    @SpirePatch2(
        cls = "hermit.HermitMod",
        method = "autoAddCards",
        optional = true
    )
    public static class HermitAutoAddCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentAutoAddCardsCalls("hermit.HermitMod");
        }
    }

    /**
     * Number of distinct archive sets currently held by the in-memory shared scan cache.
     * Exposed for the LoadoutMonsterScanProbePatches diagnostics so a device run can tell
     * "dedup engaged" (entries appear) from "call sites fell back to native scans"
     * (count stays zero).
     */
    public static int sharedCacheEntryCount() {
        synchronized (ClassFinderScanCachePatches.class) {
            return CACHE.size();
        }
    }

    public static int findClassesCached(
        ClassFinder finder,
        Collection<ClassInfo> output,
        ClassFilter filter
    ) {
        if (!isEnabled() || output == null) {
            return finder.findClasses(output, filter);
        }
        return findClassesShared(finder, output, filter);
    }

    /**
     * Shared-scan core without the cache-mode gate.
     *
     * {@link #findClassesCached} serves the Downfall autoAddCards call sites and stays
     * inactive outside cache-hit launches. Loadout's scanner threads need the same
     * deduplication on every launch regardless of the MTS patch cache state, so
     * {@code LoadoutClassFinderDedupPatches} routes its instrumented call sites here.
     * Semantics are identical to a plain {@code findClasses} call: the caller's own
     * filter still decides every entry, only the underlying archive walk is shared.
     */
    public static int findClassesShared(
        ClassFinder finder,
        Collection<ClassInfo> output,
        ClassFilter filter
    ) {
        if (output == null) {
            return finder.findClasses(output, filter);
        }
        String key = cacheKey(finder);
        if (key == null) {
            return finder.findClasses(output, filter);
        }
        CacheEntry entry = getOrBuildEntry(finder, key);
        if (entry == null) {
            return finder.findClasses(output, filter);
        }
        int accepted = filterClasses(finder, entry, output, filter);
        if (accepted < 0) {
            return finder.findClasses(output, filter);
        }
        recordReturnedClasses(accepted);
        return accepted;
    }

    public static URL codeSourceLocation(CodeSource codeSource, Class<?> ownerClass) {
        if (codeSource != null && codeSource.getLocation() != null) {
            return codeSource.getLocation();
        }
        URL resourceUrl = ownerClass.getResource("/" + ownerClass.getName().replace('.', '/') + ".class");
        if (resourceUrl == null) {
            return null;
        }
        try {
            URLConnection connection = resourceUrl.openConnection();
            if (connection instanceof JarURLConnection) {
                return ((JarURLConnection) connection).getJarFileURL();
            }
        } catch (Throwable ignored) {
        }
        String externalForm = resourceUrl.toExternalForm();
        if (externalForm.startsWith("jar:")) {
            int separator = externalForm.indexOf("!/");
            if (separator > "jar:".length()) {
                try {
                    return new URL(externalForm.substring("jar:".length(), separator));
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static ExprEditor instrumentAutoAddCardsCalls(final String ownerClassName) {
        return new ExprEditor() {
            @Override
            public void edit(MethodCall call) throws CannotCompileException {
                if (CodeSource.class.getName().equals(call.getClassName())
                    && "getLocation".equals(call.getMethodName())) {
                    call.replace(
                        "{ $_ = "
                            + ClassFinderScanCachePatches.class.getName()
                            + ".codeSourceLocation($0, "
                            + ownerClassName
                            + ".class); }"
                    );
                    return;
                }
                if (!ClassFinder.class.getName().equals(call.getClassName())) {
                    return;
                }
                if (!"findClasses".equals(call.getMethodName())) {
                    return;
                }
                call.replace(
                    "{ $_ = "
                        + ClassFinderScanCachePatches.class.getName()
                        + ".findClassesCached($0, $1, $2); }"
                );
            }
        };
    }

    private static CacheEntry getOrBuildEntry(ClassFinder finder, String key) {
        synchronized (ClassFinderScanCachePatches.class) {
            CacheEntry cached = CACHE.get(key);
            if (cached != null) {
                recordHitLocked();
                return cached;
            }
        }

        ArrayList<ClassInfo> scanned = new ArrayList<ClassInfo>();
        long startedAtNs = System.nanoTime();
        finder.findClasses(scanned, null);

        CacheEntry built = new CacheEntry(scanned);
        synchronized (ClassFinderScanCachePatches.class) {
            CacheEntry cached = CACHE.get(key);
            if (cached != null) {
                recordHitLocked();
                return cached;
            }
            CACHE.put(key, built);
            recordMissLocked();
        }
        if (isProfilingEnabled()) {
            System.out.println(
                "[amethyst-runtime-compat] ClassFinder scan cache built classes="
                    + scanned.size()
                    + " elapsedMs="
                    + elapsedMs(startedAtNs)
                    + " key="
                    + key
            );
        }
        return built;
    }

    private static int filterClasses(
        ClassFinder finder,
        CacheEntry entry,
        Collection<ClassInfo> output,
        ClassFilter filter
    ) {
        Map<String, ClassInfo> originalFoundClasses = foundClasses(finder);
        if (originalFoundClasses == null) {
            return -1;
        }
        originalFoundClasses.clear();
        originalFoundClasses.putAll(entry.byName);
        int accepted = 0;
        for (ClassInfo classInfo : entry.classes) {
            if (filter == null || filter.accept(classInfo, finder)) {
                output.add(classInfo);
                accepted++;
            }
        }
        return accepted;
    }

    private static String cacheKey(ClassFinder finder) {
        LinkedHashMap<String, File> places = placesToSearch(finder);
        if (places == null || places.isEmpty()) {
            return null;
        }
        StringBuilder key = new StringBuilder();
        for (File place : places.values()) {
            if (place == null || !place.isFile()) {
                return null;
            }
            String name = place.getName();
            if (name == null || !(endsWithIgnoreCase(name, ".jar") || endsWithIgnoreCase(name, ".zip"))) {
                return null;
            }
            key.append(place.getAbsolutePath())
                .append('|')
                .append(place.length())
                .append('|')
                .append(place.lastModified())
                .append(';');
        }
        return key.toString();
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, File> placesToSearch(ClassFinder finder) {
        Field field = placesToSearchField();
        if (field == null) {
            return null;
        }
        try {
            return (LinkedHashMap<String, File>) field.get(finder);
        } catch (Throwable ignored) {
            reflectionUnavailable = true;
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ClassInfo> foundClasses(ClassFinder finder) {
        Field field = foundClassesField();
        if (field == null) {
            return null;
        }
        try {
            return (Map<String, ClassInfo>) field.get(finder);
        } catch (Throwable ignored) {
            reflectionUnavailable = true;
            return null;
        }
    }

    private static Field placesToSearchField() {
        if (reflectionUnavailable) {
            return null;
        }
        if (placesToSearchField != null) {
            return placesToSearchField;
        }
        try {
            placesToSearchField = ClassFinder.class.getDeclaredField("placesToSearch");
            placesToSearchField.setAccessible(true);
            return placesToSearchField;
        } catch (Throwable ignored) {
            reflectionUnavailable = true;
            return null;
        }
    }

    private static Field foundClassesField() {
        if (reflectionUnavailable) {
            return null;
        }
        if (foundClassesField != null) {
            return foundClassesField;
        }
        try {
            foundClassesField = ClassFinder.class.getDeclaredField("foundClasses");
            foundClassesField.setAccessible(true);
            return foundClassesField;
        } catch (Throwable ignored) {
            reflectionUnavailable = true;
            return null;
        }
    }

    private static void recordHitLocked() {
        if (editCardsDepth > 0) {
            editCardsHits++;
        }
    }

    private static void recordMissLocked() {
        if (editCardsDepth > 0) {
            editCardsMisses++;
        }
    }

    private static void recordReturnedClasses(int count) {
        if (count <= 0) {
            return;
        }
        synchronized (ClassFinderScanCachePatches.class) {
            if (editCardsDepth > 0) {
                editCardsReturnedClasses += count;
            }
        }
    }

    private static boolean endsWithIgnoreCase(String value, String suffix) {
        return value.regionMatches(true, value.length() - suffix.length(), suffix, 0, suffix.length());
    }

    private static long elapsedMs(long startedAtNs) {
        return (System.nanoTime() - startedAtNs) / 1_000_000L;
    }

    private static boolean isEnabled() {
        return StartupCacheRuntimeConfig.isCacheFeatureEnabled(ENABLED_PROP, true);
    }

    private static boolean isProfilingEnabled() {
        return StartupCacheRuntimeConfig.readBooleanSystemProperty(PROFILE_PROP, false);
    }

    private static final class CacheEntry {
        private final List<ClassInfo> classes;
        private final Map<String, ClassInfo> byName;

        private CacheEntry(List<ClassInfo> classes) {
            this.classes = new ArrayList<ClassInfo>(classes);
            this.byName = new HashMap<String, ClassInfo>();
            for (ClassInfo classInfo : classes) {
                this.byName.put(classInfo.getClassName(), classInfo);
            }
        }
    }
}
