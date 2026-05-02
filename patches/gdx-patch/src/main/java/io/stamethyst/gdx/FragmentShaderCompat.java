package io.stamethyst.gdx;

import java.util.regex.Pattern;

public final class FragmentShaderCompat {
    private static final String ENABLED_PROP = "amethyst.gdx.fragment_shader_precision_compat";
    private static final String NATIVE_DIR_PROP = "amethyst.gdx.native_dir";
    private static final Pattern LEGACY_TEXTURE_FUNCTION_PATTERN =
        Pattern.compile("(?<![A-Za-z0-9_])texture\\s*\\(");
    private static final Pattern STANDARD_DERIVATIVE_PATTERN =
        Pattern.compile("\\b(?:fwidth|dFdx|dFdy)\\s*\\(");
    private static final Pattern STANDARD_DERIVATIVE_EXTENSION_PATTERN =
        Pattern.compile("(?m)^\\s*#extension\\s+GL_OES_standard_derivatives\\s*:");
    private static final Pattern SCALAR_FRACT_REDEFINITION_PATTERN =
        Pattern.compile(
            "(?m)^\\s*float\\s+fract\\s*\\(\\s*float\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\)\\s*\\{\\s*" +
                "return\\s+\\1\\s*-\\s*floor\\s*\\(\\s*\\1\\s*\\)\\s*;\\s*\\}\\s*(?:\\r?\\n)?"
        );
    private static final Pattern FLOAT_PRECISION_PATTERN =
        Pattern.compile("(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+float\\s*;");
    private static final Pattern INT_PRECISION_PATTERN =
        Pattern.compile("(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+int\\s*;");

    private FragmentShaderCompat() {
    }

    public static String normalizeVertexShader(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        if (!isCompatEnabled()) {
            return source;
        }

        String stripped = stripLeadingDesktopVersionDirective(source, "vertex");
        return ensureGles100VersionDirective(stripped, "vertex");
    }

    public static String normalizeFragmentShader(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        if (!isCompatEnabled()) {
            return source;
        }

        String stripped = stripLeadingDesktopVersionDirective(source, "fragment");
        String versioned = ensureGles100VersionDirective(stripped, "fragment");
        String withoutRedefinedBuiltIns = removeBuiltInFunctionRedefinitions(versioned);
        String legacyCompatible = isModernGlesVersionDirective(withoutRedefinedBuiltIns)
            ? withoutRedefinedBuiltIns
            : ensureLegacyFragmentCompatibility(withoutRedefinedBuiltIns);
        return ensureDefaultPrecisionInternal(legacyCompatible);
    }

    public static String ensureDefaultPrecision(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        if (!isCompatEnabled()) {
            return source;
        }

        return ensureDefaultPrecisionInternal(source);
    }

    private static String ensureDefaultPrecisionInternal(String source) {
        if (source == null || source.isEmpty()) {
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

    private static String stripLeadingDesktopVersionDirective(String source, String shaderType) {
        int versionIndex = findLeadingVersionDirectiveIndex(source);
        if (versionIndex < 0) {
            return source;
        }

        int lineEnd = skipLine(source, versionIndex);
        String directiveLine = source.substring(versionIndex, lineEnd).trim();
        if (!isDesktopVersionDirective(directiveLine)) {
            return source;
        }

        System.out.println(
            "[gdx-patch] Shader source compat stripped desktop GLSL version header from " +
                shaderType + " shader"
        );
        return source.substring(0, versionIndex) + source.substring(lineEnd);
    }

    private static String ensureLegacyFragmentCompatibility(String source) {
        String patched = source;
        patched = ensureStandardDerivativesExtension(patched);
        patched = LEGACY_TEXTURE_FUNCTION_PATTERN.matcher(patched).replaceAll("texture2D(");
        return patched;
    }

    private static String removeBuiltInFunctionRedefinitions(String source) {
        return SCALAR_FRACT_REDEFINITION_PATTERN.matcher(source).replaceAll("");
    }

    private static String ensureGles100VersionDirective(String source, String shaderType) {
        if (!isGlesBackedRuntime() || findLeadingVersionDirectiveIndex(source) >= 0) {
            return source;
        }

        int insertIndex = source.charAt(0) == '\ufeff' ? 1 : 0;
        String lineSeparator = detectLineSeparator(source);
        System.out.println(
            "[gdx-patch] Shader source compat added GLES 100 version header to " +
                shaderType + " shader"
        );
        return source.substring(0, insertIndex) +
            "#version 100" + lineSeparator +
            source.substring(insertIndex);
    }

    private static String ensureStandardDerivativesExtension(String source) {
        if (!STANDARD_DERIVATIVE_PATTERN.matcher(source).find() ||
            STANDARD_DERIVATIVE_EXTENSION_PATTERN.matcher(source).find()
        ) {
            return source;
        }

        String lineSeparator = detectLineSeparator(source);
        int insertIndex = findInsertIndex(source);
        StringBuilder patched = new StringBuilder(source.length() + 64);
        patched.append(source, 0, insertIndex);
        if (insertIndex > 0) {
            char previous = source.charAt(insertIndex - 1);
            if (previous != '\n' && previous != '\r') {
                patched.append(lineSeparator);
            }
        }
        patched.append("#extension GL_OES_standard_derivatives : enable").append(lineSeparator);
        patched.append(source, insertIndex, source.length());
        return patched.toString();
    }

    private static int findLeadingVersionDirectiveIndex(String source) {
        int cursor = 0;
        if (source.charAt(0) == '\ufeff') {
            cursor = 1;
        }
        int candidate = skipTrivia(source, cursor);
        return startsWithDirective(source, candidate, "#version") ? candidate : -1;
    }

    private static boolean isDesktopVersionDirective(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length < 2 || !"#version".equalsIgnoreCase(tokens[0])) {
            return false;
        }
        for (String token : tokens) {
            if ("es".equalsIgnoreCase(token)) {
                return false;
            }
        }
        try {
            return Integer.parseInt(tokens[1]) >= 110;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isModernGlesVersionDirective(String source) {
        int versionIndex = findLeadingVersionDirectiveIndex(source);
        if (versionIndex < 0) {
            return false;
        }

        int lineEnd = skipLine(source, versionIndex);
        String[] tokens = source.substring(versionIndex, lineEnd).trim().split("\\s+");
        if (tokens.length < 3 || !"#version".equalsIgnoreCase(tokens[0])) {
            return false;
        }
        if (!"es".equalsIgnoreCase(tokens[2])) {
            return false;
        }
        try {
            return Integer.parseInt(tokens[1]) >= 300;
        } catch (NumberFormatException ignored) {
            return false;
        }
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

    private static boolean isGlesBackedRuntime() {
        if (System.getProperty(NATIVE_DIR_PROP) != null) {
            return true;
        }
        return System.getProperty("os.version", "").startsWith("Android-");
    }
}
