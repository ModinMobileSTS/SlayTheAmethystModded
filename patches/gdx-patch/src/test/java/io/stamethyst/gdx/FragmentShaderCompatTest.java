package io.stamethyst.gdx;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FragmentShaderCompatTest {
    private static final String ENABLED_PROP = "amethyst.gdx.fragment_shader_precision_compat";
    private static final String NATIVE_DIR_PROP = "amethyst.gdx.native_dir";

    @Test
    public void ensureDefaultPrecision_respectsDisabledProperty() {
        String original = "void main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "false");
            assertEquals(original, FragmentShaderCompat.ensureDefaultPrecision(original));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void ensureDefaultPrecision_injectsPrecisionWhenEnabled() {
        String original = "void main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.ensureDefaultPrecision(original);
            assertTrue(patched.contains("precision highp float;"));
            assertTrue(patched.contains("precision highp int;"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeVertexShader_stripsDesktopVersionHeader() {
        String original = "#version 120\nattribute vec4 a_position;\nvoid main() {\n    gl_Position = a_position;\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeVertexShader(original);
            assertFalse(patched.contains("#version 120"));
            assertTrue(patched.contains("attribute vec4 a_position;"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeVertexShader_keepsGlesVersionHeader() {
        String original = "#version 300 es\nin vec4 a_position;\nvoid main() {\n    gl_Position = a_position;\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            assertEquals(original, FragmentShaderCompat.normalizeVertexShader(original));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeVertexShader_addsGles100VersionOnAndroidRuntime() {
        String original = "#version 120\nattribute vec4 a_position;\nvoid main() {\n    gl_Position = a_position;\n}\n";
        String previousEnabled = System.getProperty(ENABLED_PROP);
        String previousNativeDir = System.getProperty(NATIVE_DIR_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            System.setProperty(NATIVE_DIR_PROP, "/tmp/native");
            String patched = FragmentShaderCompat.normalizeVertexShader(original);
            assertFalse(patched.contains("#version 120"));
            assertTrue(patched.startsWith("#version 100\n"));
            assertTrue(patched.contains("attribute vec4 a_position;"));
        } finally {
            restoreProperty(ENABLED_PROP, previousEnabled);
            restoreProperty(NATIVE_DIR_PROP, previousNativeDir);
        }
    }

    @Test
    public void normalizeFragmentShader_stripsDesktopVersionAndInjectsPrecision() {
        String original = "#version 120\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertFalse(patched.contains("#version 120"));
            assertTrue(patched.contains("precision highp float;"));
            assertTrue(patched.contains("gl_FragColor"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_addsGles100VersionOnAndroidRuntime() {
        String original = "#version 120\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        String previousEnabled = System.getProperty(ENABLED_PROP);
        String previousNativeDir = System.getProperty(NATIVE_DIR_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            System.setProperty(NATIVE_DIR_PROP, "/tmp/native");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertFalse(patched.contains("#version 120"));
            assertTrue(patched.startsWith("#version 100\n"));
            assertTrue(patched.indexOf("#version 100") < patched.indexOf("precision highp float;"));
        } finally {
            restoreProperty(ENABLED_PROP, previousEnabled);
            restoreProperty(NATIVE_DIR_PROP, previousNativeDir);
        }
    }

    @Test
    public void normalizeFragmentShader_rewritesTextureFunctionForLegacyShader() {
        String original = "uniform sampler2D u_texture;\nvoid main() {\n" +
            "    gl_FragColor = texture(u_texture, vec2(0.5));\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertFalse(patched.contains("texture(u_texture"));
            assertTrue(patched.contains("texture2D(u_texture"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_keepsTextureFunctionForModernGlesShader() {
        String original = "#version 300 es\nprecision mediump float;\n" +
            "uniform sampler2D u_texture;\nout vec4 fragColor;\nvoid main() {\n" +
            "    fragColor = texture(u_texture, vec2(0.5));\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertTrue(patched.contains("#version 300 es"));
            assertTrue(patched.contains("texture(u_texture"));
            assertFalse(patched.contains("texture2D(u_texture"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_enablesDerivativeExtensionForLegacyShader() {
        String original = "void main() {\n    float width = fwidth(1.0);\n" +
            "    gl_FragColor = vec4(width);\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "true");
            String patched = FragmentShaderCompat.normalizeFragmentShader(original);
            assertTrue(patched.contains("#extension GL_OES_standard_derivatives : enable"));
            assertTrue(patched.indexOf("#extension GL_OES_standard_derivatives : enable") <
                patched.indexOf("precision highp float;"));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    public void normalizeFragmentShader_respectsDisabledProperty() {
        String original = "#version 120\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n";
        String previous = System.getProperty(ENABLED_PROP);
        try {
            System.setProperty(ENABLED_PROP, "false");
            assertEquals(original, FragmentShaderCompat.normalizeFragmentShader(original));
        } finally {
            restoreProperty(previous);
        }
    }

    private static void restoreProperty(String previous) {
        restoreProperty(ENABLED_PROP, previous);
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }
}
