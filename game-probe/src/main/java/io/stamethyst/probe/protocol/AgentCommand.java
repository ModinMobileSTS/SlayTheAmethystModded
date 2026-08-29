package io.stamethyst.probe.protocol;

public enum AgentCommand {
    ATTACH,
    DETACH,
    LIST,
    STATUS,
    SUBSCRIBE,
    UNSUBSCRIBE,
    QUIT,
    OBSERVE,
    READY,
    EXEC,
    PERF_START,
    PERF_STOP,
    DUMP_CLASS,
    REDEFINE_CLASS,
    LOAD_AGENT,
    CONSOLE;

    public static AgentCommand parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("command text is null");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("command text is empty");
        }
        try {
            return valueOf(trimmed.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown command: " + trimmed);
        }
    }
}
