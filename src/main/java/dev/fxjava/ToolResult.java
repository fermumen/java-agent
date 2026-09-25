package dev.fxjava;

import java.util.Objects;

/** Internal tool output plus a status that does not depend on display text. */
public final class ToolResult {
    public enum Status { SUCCESS, ERROR, TIMEOUT, CANCELLED, UNCERTAIN }

    private final String output;
    private final Status status;

    private ToolResult(String output, Status status) {
        this.output = Objects.requireNonNull(output, "output");
        this.status = Objects.requireNonNull(status, "status");
    }

    public static ToolResult success(String output) {
        return new ToolResult(output, Status.SUCCESS);
    }

    public static ToolResult error(String output) {
        return new ToolResult(output, Status.ERROR);
    }

    public static ToolResult timeout(String output) {
        return new ToolResult(output, Status.TIMEOUT);
    }

    public static ToolResult cancelled(String output) {
        return new ToolResult(output, Status.CANCELLED);
    }

    public static ToolResult uncertain(String output) {
        return new ToolResult(output, Status.UNCERTAIN);
    }

    public String output() { return output; }
    public Status status() { return status; }
    public boolean isError() { return status != Status.SUCCESS; }
}
