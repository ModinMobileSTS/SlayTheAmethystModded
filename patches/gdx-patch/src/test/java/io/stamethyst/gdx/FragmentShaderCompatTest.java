package io.stamethyst.gdx;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FragmentShaderCompatTest {
    private static final String ENABLED_PROP = "amethyst.gdx.fragment_shader_precision_compat";

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
        if (previous == null) {
            System.clearProperty(ENABLED_PROP);
        } else {
            System.setProperty(ENABLED_PROP, previous);
        }
    }
}
