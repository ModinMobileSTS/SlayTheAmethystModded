package io.stamethyst.compatmod.compatibility;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import io.stamethyst.compatmod.core.StartupCacheRuntimeConfig;

import org.clapper.util.classutil.ClassFilter;
import org.clapper.util.classutil.ClassFinder;
import org.clapper.util.classutil.ClassInfo;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.util.Collection;

/**
 * Shares one ClassFinder archive walk across every Loadout scanner that reads the same
 * jar, on every launch regardless of the MTS patch cache state.
 *
 * Loadout spawns one worker per kind per mod (CardModAdder, PowerAdder, OrbAdder,
 * MonsterAdder) and each worker builds its own {@link ClassFinder} over the same mod
 * jar, so a large mod set walks the same archives four times over. The instrumented
 * call sites below route those walks through
 * {@link ClassFinderScanCachePatches#findClassesShared}, where the first caller scans
 * the archive once into an in-memory ClassInfo list and every later caller applies its
 * own filter to the shared list. Filter semantics, result order, and Loadout's own
 * threading model are unchanged; only the repeated archive walk disappears.
 * This deliberately does not replace Loadout's workers with synchronous map fills the
 * way the retired {@code LoadoutClassScanCachePatches} did: that design serialized the
 * work onto the startup thread and revalidated cached class names through eager class
 * loading, which cost more than the scans it saved.
 *
 * Disable with {@code amethyst.runtime_compat.loadout_class_finder_dedup=false}.
 */
public final class LoadoutClassFinderDedupPatches {
    private static final String ENABLED_PROP = "amethyst.runtime_compat.loadout_class_finder_dedup";
    /**
     * org.clapper.util.classutil.ClassFinder.findClasses(Collection, ClassFilter).
     * Guarding on the exact descriptor keeps other findClasses overloads untouched,
     * since Javassist matches call sites by method name alone.
     */
    private static final String FIND_CLASSES_DESCRIPTOR =
        "(Ljava/util/Collection;Lorg/clapper/util/classutil/ClassFilter;)I";

    private LoadoutClassFinderDedupPatches() {
    }

    @SpirePatch2(
        cls = "loadout.util.CardModAdder",
        method = "run",
        requiredModId = "loadout",
        optional = true
    )
    public static class CardModAdderRunPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentFindClasses("loadout.util.CardModAdder");
        }
    }

    @SpirePatch2(
        cls = "loadout.util.PowerAdder",
        method = "run",
        requiredModId = "loadout",
        optional = true
    )
    public static class PowerAdderRunPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentFindClasses("loadout.util.PowerAdder");
        }
    }

    @SpirePatch2(
        cls = "loadout.util.OrbAdder",
        method = "run",
        requiredModId = "loadout",
        optional = true
    )
    public static class OrbAdderRunPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentFindClasses("loadout.util.OrbAdder");
        }
    }

    @SpirePatch2(
        cls = "loadout.util.MonsterAdder",
        method = "run",
        requiredModId = "loadout",
        optional = true
    )
    public static class MonsterAdderRunPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return instrumentFindClasses("loadout.util.MonsterAdder");
        }
    }

    // Deliberately no instrumentation of LoadoutMod.addBaseGameMonsters here: that
    // method is instrumented by the diagnostic LoadoutMonsterScanProbePatches instead,
    // and two ExprEditors rewriting the same call site would interfere. The probe's
    // wrapper routes through findClassesShared as well, so the monster scan still
    // benefits from the shared archive walk whenever it is probed. The method has a
    // single findClasses call per launch, so skipping it here costs no deduplication.

    private static ExprEditor instrumentFindClasses(final String ownerClassName) {
        return new ExprEditor() {
            @Override
            public void edit(MethodCall call) throws CannotCompileException {
                if (!ClassFinder.class.getName().equals(call.getClassName())) {
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
                        + LoadoutClassFinderDedupPatches.class.getName()
                        + ".findClassesDedup($0, $1, $2); }"
                );
            }
        };
    }

    public static int findClassesDedup(
        ClassFinder finder,
        Collection<ClassInfo> output,
        ClassFilter filter
    ) {
        if (!isEnabled()) {
            return finder.findClasses(output, filter);
        }
        return ClassFinderScanCachePatches.findClassesShared(finder, output, filter);
    }

    private static boolean isEnabled() {
        return StartupCacheRuntimeConfig.readBooleanSystemProperty(ENABLED_PROP, true);
    }
}
