package io.stamethyst;

public enum RendererBackend {
    OPENGL_ES2("opengles2", "OpenGL ES2 (default)", "OpenGL ES2"),
    KOPPER_ZINK("opengles3_desktopgl_zink_kopper", "Mesa Zink (Kopper)", "Mesa Zink (Kopper)");

    private final String rendererId;
    private final String selectorLabel;
    private final String statusLabel;

    RendererBackend(String rendererId, String selectorLabel, String statusLabel) {
        this.rendererId = rendererId;
        this.selectorLabel = selectorLabel;
        this.statusLabel = statusLabel;
    }

    public String rendererId() {
        return rendererId;
    }

    public String selectorLabel() {
        return selectorLabel;
    }

    public String statusLabel() {
        return statusLabel;
    }

    public String lwjglOpenGlLibName() {
        return this == KOPPER_ZINK ? "libglxshim.so" : "libGLESv2.so";
    }

    public static RendererBackend fromRendererId(String value) {
        if (value != null) {
            String normalized = value.trim();
            for (RendererBackend backend : values()) {
                if (backend.rendererId.equalsIgnoreCase(normalized)) {
                    return backend;
                }
            }
        }
        return OPENGL_ES2;
    }
}
