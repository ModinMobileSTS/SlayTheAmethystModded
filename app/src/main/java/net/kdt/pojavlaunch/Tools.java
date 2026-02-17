package net.kdt.pojavlaunch;

public final class Tools {
    private Tools() {
    }

    public static class SDL {
        public static native void initializeControllerSubsystems();
    }
}
