package optispire;

/** Startup-only A/B flags. Never read Android preferences or system properties in frame hot paths. */
public final class RamSaverFeatures {
    private static boolean sharedReleaseBudget = Boolean.parseBoolean(System.getProperty("ramsaver.release.shared_budget_enabled", "false"));

    private RamSaverFeatures() { }
    public static boolean sharedReleaseBudget() { return sharedReleaseBudget; }
}
