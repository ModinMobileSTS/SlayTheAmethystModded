package io.stamethyst.gdx;

import java.util.regex.Pattern;

public final class FragmentShaderCompat {
    private static final String ENABLED_PROP = "amethyst.gdx.fragment_shader_precision_compat";
    private static final Pattern FLOAT_PRECISION_PATTERN =
        Pattern.compile("(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+float\\s*;");
    private static final Pattern INT_PRECISION_PATTERN =
        Pattern.compile("(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+int\\s*;");

    private FragmentShaderCompat() {
    }

    public static String ensureDefaultPrecision(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        if (!isCompatEnabled()) {
            return source;
        }

        boolean missingFloatPrecision = !FLOAT_PRECISION_PATTERN.matcher(source).find();
        boolean missingIntPrecision = !INT_PRECISION_PATTERN.matcher(source).find();
        if (!missingFloatPrecision && !missingIntPrecision) {
            return source;
        }

        String lineSeparator = detectLineSeparator(source);
        int insertIndex = findInsertIndex(source);
        StringBuilder patched = new StringBuilder(source.length() + 160);
        patched.append(source, 0, insertIndex);
        if (insertIndex > 0) {
            char previous = source.charAt(insertIndex - 1);
            if (previous != '\n' && previous != '\r') {
                patched.append(lineSeparator);
            }
        }
        appendPrecisionBlock(
            patched,
            lineSeparator,
            missingFloatPrecision,
            missingIntPrecision
        );
        patched.append(source, insertIndex, source.length());
        return patched.toString();
    }

    private static void appendPrecisionBlock(
        StringBuilder out,
        String lineSeparator,
        boolean missingFloatPrecision,
        boolean missingIntPrecision
    ) {
        out.append("#ifdef GL_ES").append(lineSeparator);
        out.append("#ifdef GL_FRAGMENT_PRECISION_HIGH").append(lineSeparator);
        if (missingFloatPrecision) {
            out.append("precision highp float;").append(lineSeparator);
        }
        if (missingIntPrecision) {
            out.append("precision highp int;").append(lineSeparator);
        }
        out.append("#else").append(lineSeparator);
        if (missingFloatPrecision) {
            out.append("precision mediump float;").append(lineSeparator);
        }
        if (missingIntPrecision) {
            out.append("precision mediump int;").append(lineSeparator);
        }
        out.append("#endif").append(lineSeparator);
        out.append("#endif").append(lineSeparator);
    }

    private static int findInsertIndex(String source) {
        int cursor = 0;
        if (source.charAt(0) == '\ufeff') {
            cursor = 1;
        }
        cursor = skipTrivia(source, cursor);
        if (startsWithDirective(source, cursor, "#version")) {
            cursor = skipLine(source, cursor);
        }
        while (true) {
            int next = skipTrivia(source, cursor);
            if (!startsWithDirective(source, next, "#extension")) {
                return next;
            }
            cursor = skipLine(source, next);
        }
    }

    private static int skipTrivia(String source, int start) {
        int index = start;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '/' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    index = skipLine(source, index);
                    continue;
                }
                if (next == '*') {
                    int end = source.indexOf("*/", index + 2);
                    if (end < 0) {
                        return source.length();
                    }
                    index = end + 2;
                    continue;
                }
            }
            break;
        }
        return index;
    }

    private static int skipLine(String source, int start) {
        int index = start;
        while (index < source.length()) {
            char current = source.charAt(index++);
            if (current == '\n') {
                return index;
            }
            if (current == '\r') {
                if (index < source.length() && source.charAt(index) == '\n') {
                    index++;
                }
                return index;
            }
        }
        return source.length();
    }

    private static boolean startsWithDirective(String source, int index, String directive) {
        if (index < 0 || index + directive.length() > source.length()) {
            return false;
        }
        if (!source.regionMatches(index, directive, 0, directive.length())) {
            return false;
        }
        int nextIndex = index + directive.length();
        return nextIndex >= source.length() || Character.isWhitespace(source.charAt(nextIndex));
    }

    private static String detectLineSeparator(String source) {
        int newlineIndex = source.indexOf('\n');
        if (newlineIndex > 0 && source.charAt(newlineIndex - 1) == '\r') {
            return "\r\n";
        }
        return "\n";
    }

    private static boolean isCompatEnabled() {
        String configured = System.getProperty(ENABLED_PROP);
        if (configured == null) {
            return true;
        }
        String normalized = configured.trim();
        if (normalized.isEmpty()) {
            return true;
        }
        if ("0".equals(normalized) || "false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return true;
    }
}
