package io.stamethyst.compatmod.diagnostics;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;

import io.stamethyst.compatmod.compatibility.ClassFinderScanCachePatches;
import io.stamethyst.compatmod.core.StartupCacheRuntimeConfig;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import javassist.expr.NewExpr;

import org.clapper.util.classutil.ClassFilter;
import org.clapper.util.classutil.ClassFinder;
import org.clapper.util.classutil.ClassInfo;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.security.CodeSource;
import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * Root-cause probe for Loadout's {@code addBaseGameMonsters} failing with an empty
 * monster map on cache-hit launches. Diagnostic only: every hook observes and logs,
 * none of them changes behavior, and everything is inert unless the probe property is
 * set.
 *
 * Enable with {@code amethyst.runtime_compat.loadout_monster_scan_probe=true}. The
 * property is wired through the debug-build JVM property allowlist in StsLaunchSpec so
 * a device run only needs the debug prefs file flipped.
 *
 * The probe answers four questions per launch:
 * <ul>
 *   <li>Where does the scan run from? (working directory plus which candidate base-jar
 *       files exist relative to it)</li>
 *   <li>Which archive does it point ClassFinder at? (placesToSearch dump before each
 *       findClasses call)</li>
 *   <li>Does resolving a class's code source fail under the cached loader? (every
 *       CodeSource.getLocation call inside the method is logged with its outcome)</li>
 *   <li>What did the scan actually return? (per-call duration and accepted count, and
 *       the final monsterMap size after the method returns)</li>
 * </ul>
 */
public final class LoadoutMonsterScanProbePatches {
    private static final String ENABLED_PROP = "amethyst.runtime_compat.loadout_monster_scan_probe";
    private static final String DEDUP_PROP = "amethyst.runtime_compat.loadout_class_finder_dedup";
    private static final String LOG_PREFIX = "[amethyst-runtime-compat] LoadoutMonsterScanProbe ";
    private static final String FIND_CLASSES_DESCRIPTOR =
        "(Ljava/util/Collection;Lorg/clapper/util/classutil/ClassFilter;)I";

    private static volatile Field placesToSearchField;
    private static volatile boolean reflectionUnavailable;

    private LoadoutMonsterScanProbePatches() {
    }

    @SpirePatch2(
        cls = "loadout.LoadoutMod",
        method = "addBaseGameMonsters",
        requiredModId = "loadout",
        optional = true
    )
    public static class AddBaseGameMonstersProbe {
        @SpirePrefixPatch
        public static void Prefix() {
            if (!isEnabled()) {
                return;
            }
            try {
                String cwd = new File("").getAbsolutePath();
                log("enter cwd=" + cwd
                    + " desktopJar=" + describeCandidateFile("desktop-1.0.jar")
                    + " moddedJar=" + describeCandidateFile("desktop-1.0-modded.jar")
                    + " loaderStsJar=" + readLoaderStsJar()
                    + " dedupProp=" + StartupCacheRuntimeConfig.readBooleanSystemProperty(DEDUP_PROP, true)
                    + " sharedCacheEntries=" + ClassFinderScanCachePatches.sharedCacheEntryCount());
            } catch (Throwable error) {
                log("enter logging failed: " + error);
            }
        }

        @SpirePostfixPatch
        public static void Postfix() {
            if (!isEnabled()) {
                return;
            }
            try {
                log("exit monsterMapSize=" + readStaticMapSize("loadout.LoadoutMod", "monsterMap")
                    + " baseGameMonsterMapSize=" + readStaticMapSize("loadout.LoadoutMod", "baseGameMonsterMap")
                    + " sharedCacheEntries=" + ClassFinderScanCachePatches.sharedCacheEntryCount());
            } catch (Throwable error) {
                log("exit logging failed: " + error);
            }
        }

        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(NewExpr call) throws CannotCompileException {
                    // new File(...) inside addBaseGameMonsters: log the path being built
                    // and whether it exists, without changing construction.
                    if (!"java.io.File".equals(call.getClassName())) {
                        return;
                    }
                    String signature = call.getSignature();
                    if ("(Ljava/lang/String;)V".equals(signature)) {
                        call.replace(
                            "{ $_ = $proceed($1); "
                                + LoadoutMonsterScanProbePatches.class.getName()
                                + ".logFileCandidatePath($1); }"
                        );
                    } else if ("(Ljava/io/File;Ljava/lang/String;)V".equals(signature)) {
                        call.replace(
                            "{ $_ = $proceed($1, $2); "
                                + LoadoutMonsterScanProbePatches.class.getName()
                                + ".logFileCandidatePath($2); }"
                        );
                    }
                }

                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    String className = call.getClassName();
                    if (CodeSource.class.getName().equals(className)
                        && "getLocation".equals(call.getMethodName())) {
                        call.replace(
                            "{ $_ = "
                                + LoadoutMonsterScanProbePatches.class.getName()
                                + ".probeCodeSourceLocation($0); }"
                        );
                        return;
                    }
                    if ("java.lang.Class".equals(className) && "forName".equals(call.getMethodName())) {
                        call.replace(
                            "{ $_ = "
                                + LoadoutMonsterScanProbePatches.class.getName()
                                + ".probeClassForName($0, $args); }"
                        );
                        return;
                    }
                    if (("java.util.Map".equals(className) || "java.util.HashMap".equals(className)
                        || "java.util.concurrent.ConcurrentHashMap".equals(className))
                        && "put".equals(call.getMethodName())) {
                        call.replace(
                            "{ $_ = "
                                + LoadoutMonsterScanProbePatches.class.getName()
                                + ".probeMapPut($0, $1, $2); }"
                        );
                        return;
                    }
                    if (ClassInfo.class.getName().equals(className) && "getClassName".equals(call.getMethodName())) {
                        call.replace(
                            "{ $_ = "
                                + LoadoutMonsterScanProbePatches.class.getName()
                                + ".probeClassInfoName($0); }"
                        );
                        return;
                    }
                    if (!ClassFinder.class.getName().equals(className)) {
                        return;
                    }
                    if (!"findClasses".equals(call.getMethodName())) {
                        return;
                    }
                    if (!FIND_CLASSES_DESCRIPTOR.equals(call.getSignature())) {
                        return;
                    }
                    call.replace(
                        "{ $_ = "
                            + LoadoutMonsterScanProbePatches.class.getName()
                            + ".probeFindClasses($0, $1, $2); }"
                    );
                }
            };
        }
    }

    /**
     * Final-state sample: whatever happened inside addBaseGameMonsters, this records both
     * monster maps once every mod subscriber had its chance to add entries. Loadout keeps
     * mod monsters in {@code monsterMap} and base-game monsters in the separate
     * {@code baseGameMonsterMap}, so both are needed to judge the outcome.
     */
    @SpirePatch2(
        clz = basemod.BaseMod.class,
        method = "postInitialize",
        requiredModId = "basemod",
        optional = true
    )
    public static class BaseModPostInitializeProbe {
        @SpirePostfixPatch
        public static void Postfix() {
            if (!isEnabled()) {
                return;
            }
            try {
                log("postInitialize monsterMapSize=" + readStaticMapSize("loadout.LoadoutMod", "monsterMap")
                    + " baseGameMonsterMapSize=" + readStaticMapSize("loadout.LoadoutMod", "baseGameMonsterMap"));
            } catch (Throwable error) {
                log("postInitialize logging failed: " + error);
            }
        }
    }

    /**
     * Pass-through wrapper around {@code CodeSource.getLocation} that records whether
     * the call succeeded, returned null, or threw. Deliberately rethrows so the native
     * failure path stays intact while it is being diagnosed.
     */
    public static URL probeCodeSourceLocation(CodeSource codeSource) {
        long startedAtNs = System.nanoTime();
        try {
            URL location = codeSource == null ? null : codeSource.getLocation();
            log("codeSource location=" + location
                + " codeSource=" + (codeSource == null ? "null" : "present")
                + " elapsedMs=" + elapsedMs(startedAtNs));
            return location;
        } catch (Throwable error) {
            log("codeSource THREW " + error.getClass().getName() + ": " + error.getMessage()
                + " elapsedMs=" + elapsedMs(startedAtNs));
            throw error;
        }
    }

    /**
     * Pass-through wrapper around {@code ClassFinder.findClasses(Collection, ClassFilter)}
     * that dumps the finder's search places, the filter type, and the accepted count.
     * The scan itself routes through the shared ClassFinder walk when
     * {@code amethyst.runtime_compat.loadout_class_finder_dedup} is enabled, so probing
     * and deduplication compose instead of competing for the same call site.
     */
    public static int probeFindClasses(
        ClassFinder finder,
        Collection<ClassInfo> output,
        ClassFilter filter
    ) {
        long startedAtNs = System.nanoTime();
        log("findClasses enter places=" + describePlaces(finder)
            + " filter=" + (filter == null ? "null" : filter.getClass().getName()));
        try {
            int returned;
            if (StartupCacheRuntimeConfig.readBooleanSystemProperty(DEDUP_PROP, true)) {
                returned = ClassFinderScanCachePatches.findClassesShared(finder, output, filter);
            } else {
                returned = finder.findClasses(output, filter);
            }
            log("findClasses exit returned=" + returned
                + " elapsedMs=" + elapsedMs(startedAtNs));
            return returned;
        } catch (Throwable error) {
            log("findClasses THREW " + error.getClass().getName() + ": " + error.getMessage()
                + " elapsedMs=" + elapsedMs(startedAtNs));
            throw error;
        }
    }

    /**
     * Unique method name on purpose: javassist compiles the replacement source against
     * the classpool's view of this class, and overloads give that resolution one more
     * way to fail. Both constructor shapes funnel here as strings.
     */
    /**
     * Pass-through wrapper around {@code Class.forName} that records every candidate
     * Loadout resolves inside addBaseGameMonsters and whether resolution threw. Handles
     * the Java 8 overloads by arity; the game runs on Java 8 so the Module overload does
     * not exist here.
     */
    public static Class<?> probeClassForName(Object[] args) throws ClassNotFoundException {
        long startedAtNs = System.nanoTime();
        try {
            Class<?> result;
            String target;
            if (args.length == 1) {
                target = String.valueOf(args[0]);
                result = Class.forName((String) args[0]);
            } else if (args.length == 3) {
                target = String.valueOf(args[0]);
                result = Class.forName((String) args[0], (Boolean) args[1], (ClassLoader) args[2]);
            } else {
                target = "arity" + args.length;
                result = null;
            }
            log("forName " + target + " -> " + (result == null ? "null" : result.getName())
                + " elapsedMs=" + elapsedMs(startedAtNs));
            return result;
        } catch (ClassNotFoundException | LinkageError error) {
            log("forName THREW " + error.getClass().getName() + ": " + error.getMessage()
                + " elapsedMs=" + elapsedMs(startedAtNs));
            throw error;
        } catch (Throwable error) {
            log("forName THREW " + error.getClass().getName() + ": " + error.getMessage()
                + " elapsedMs=" + elapsedMs(startedAtNs));
            throw new RuntimeException(error);
        }
    }

    /** Pass-through wrapper around {@code Map.put} inside addBaseGameMonsters. */
    public static Object probeMapPut(java.util.Map<Object, Object> map, Object key, Object value) {
        Object previous = map.put(key, value);
        log("map put key=" + key
            + " value=" + (value == null ? "null" : value.getClass().getName())
            + " sizeAfter=" + map.size()
            + " previous=" + (previous == null ? "null" : "present"));
        return previous;
    }

    /** Pass-through wrapper around {@code ClassInfo.getClassName} inside the scan loop. */
    public static String probeClassInfoName(org.clapper.util.classutil.ClassInfo classInfo) {
        String name = classInfo.getClassName();
        log("candidate " + name);
        return name;
    }

    public static void logFileCandidatePath(String path) {
        try {
            log("file candidate=" + (path == null ? "null" : new File(path).getAbsolutePath())
                + " exists=" + (path != null && new File(path).isFile()));
        } catch (Throwable ignored) {
        }
    }

    private static String describeCandidateFile(String name) {
        File file = new File(name);
        return name + "(exists=" + file.isFile() + ")";
    }

    private static String describePlaces(ClassFinder finder) {
        try {
            Field field = placesToSearchField();
            if (field == null) {
                return "unresolved";
            }
            Object places = field.get(finder);
            if (!(places instanceof LinkedHashMap)) {
                return String.valueOf(places);
            }
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object value : ((LinkedHashMap<?, ?>) places).values()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                if (value instanceof File) {
                    File place = (File) value;
                    builder.append(place.getAbsolutePath())
                        .append("(exists=").append(place.isFile()).append(')');
                } else {
                    builder.append(value);
                }
            }
            return builder.append(']').toString();
        } catch (Throwable error) {
            return "unreadable:" + error.getClass().getSimpleName();
        }
    }

    private static int readStaticMapSize(String className, String fieldName) throws Exception {
        Object map = readStaticFieldValue(className, fieldName);
        if (!(map instanceof java.util.Map)) {
            return -1;
        }
        return ((java.util.Map<?, ?>) map).size();
    }

    /**
     * Reads ModTheSpire's {@code Loader.STS_JAR} — the path addBaseGameMonsters hands to
     * ClassFinder. Under the cached prepackaged launcher this static is the prime suspect
     * for a silently empty scan, so its value and existence are logged at entry.
     */
    private static String readLoaderStsJar() {
        try {
            Class<?> loader = Class.forName(
                "com.evacipated.cardcrawl.modthespire.Loader",
                false,
                LoadoutMonsterScanProbePatches.class.getClassLoader()
            );
            Object value = loader.getField("STS_JAR").get(null);
            if (!(value instanceof String)) {
                return String.valueOf(value);
            }
            File jar = new File((String) value);
            return (String) value + "(exists=" + jar.isFile() + ")";
        } catch (Throwable error) {
            return "unreadable:" + error.getClass().getSimpleName();
        }
    }

    private static Object readStaticFieldValue(String className, String fieldName) throws Exception {
        Class<?> cls = Class.forName(className, false, LoadoutMonsterScanProbePatches.class.getClassLoader());
        Class<?> current = cls;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(null);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(className + "#" + fieldName);
    }

    private static Field placesToSearchField() throws Exception {
        Field field = placesToSearchField;
        if (field != null) {
            return field;
        }
        if (reflectionUnavailable) {
            return null;
        }
        try {
            Class<?> cls = Class.forName(
                "org.clapper.util.classutil.ClassFinder",
                false,
                LoadoutMonsterScanProbePatches.class.getClassLoader()
            );
            Field resolved = cls.getDeclaredField("placesToSearch");
            resolved.setAccessible(true);
            placesToSearchField = resolved;
            return resolved;
        } catch (Throwable error) {
            reflectionUnavailable = true;
            return null;
        }
    }

    private static boolean isEnabled() {
        return StartupCacheRuntimeConfig.readBooleanSystemProperty(ENABLED_PROP, false);
    }

    private static long elapsedMs(long startedAtNs) {
        return (System.nanoTime() - startedAtNs) / 1_000_000L;
    }

    private static void log(String message) {
        System.out.println(LOG_PREFIX + message);
    }
}
