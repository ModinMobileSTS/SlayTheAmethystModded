package io.stamethyst.gdx;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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

    private static void restoreProperty(String previous) {
        if (previous == null) {
            System.clearProperty(ENABLED_PROP);
        } else {
            System.setProperty(ENABLED_PROP, previous);
        }
    }
}
