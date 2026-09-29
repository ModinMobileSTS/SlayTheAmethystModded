package javax.lang.model;

/**
 * Minimal Android shim for the JDK's {@code javax.lang.model.SourceVersion}.
 *
 * ART does not ship {@code javax.lang.model}, but ECJ's batch {@code FileSystem} static initializer
 * probes {@code SourceVersion.valueOf("RELEASE_12")} to detect a JDK 12+ environment. Returning
 * {@code null} makes ECJ treat the environment as pre-Java-12, which is correct because patch
 * compilation is pinned to Java 8. Only that single call site is reached on the non-annotation-
 * processing batch path.
 */
public final class SourceVersion {
    private SourceVersion() {}

    public static SourceVersion valueOf(String name) {
        return null;
    }

    public static SourceVersion[] values() {
        return new SourceVersion[0];
    }
}
