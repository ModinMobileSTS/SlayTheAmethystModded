package io.stamethyst.probe.protocol;

public class AgentRequest {
    private final AgentCommand command;
    private final String spec;
    private final String target;
    private final String argsJson;

    private AgentRequest(AgentCommand command, String spec, String target, String argsJson) {
        this.command = command;
        this.spec = spec;
        this.target = target;
        this.argsJson = argsJson;
    }

    public AgentCommand getCommand() { return command; }
    public String getSpec() { return spec; }
    public String getTarget() { return target; }
    public String getArgsJson() { return argsJson; }

    public static AgentRequest parse(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("request line is empty");
        }

        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace < 0) {
            AgentCommand cmd = AgentCommand.parse(trimmed);
            switch (cmd) {
                case LIST:
                case QUIT:
                case OBSERVE:
                case READY:
                    return new AgentRequest(cmd, null, null, null);
                case DETACH:
                    throw new IllegalArgumentException("DETACH requires a target agent ID");
                case ATTACH:
                    throw new IllegalArgumentException("ATTACH requires a spec");
                case CONSOLE:
                    throw new IllegalArgumentException("CONSOLE requires a command string");
                default:
                    throw new IllegalArgumentException("missing required argument");
            }
        }

        String cmdPart = trimmed.substring(0, firstSpace);
        AgentCommand cmd = AgentCommand.parse(cmdPart);
        String remaining = trimmed.substring(firstSpace + 1).trim();

        switch (cmd) {
            case ATTACH:
            case EXEC:
            case LOAD_AGENT:
                return parseTwoArg(cmd, remaining);
            case DETACH:
            case STATUS:
            case SUBSCRIBE:
            case UNSUBSCRIBE:
                return new AgentRequest(cmd, null, remaining, null);
            case PERF_START:
            case PERF_STOP:
            case DUMP_CLASS:
            case REDEFINE_CLASS:
            case CONSOLE:
                return new AgentRequest(cmd, remaining, null, null);
            default:
                throw new IllegalArgumentException("unexpected command with arguments: " + cmd);
        }
    }

    private static AgentRequest parseTwoArg(AgentCommand cmd, String remaining) {
        int specEnd = remaining.indexOf(' ');
        if (specEnd < 0) {
            return new AgentRequest(cmd, remaining, null, "{}");
        }
        String spec = remaining.substring(0, specEnd);
        String args = remaining.substring(specEnd + 1).trim();
        if (args.isEmpty()) {
            args = "{}";
        }
        return new AgentRequest(cmd, spec, null, args);
    }
}
