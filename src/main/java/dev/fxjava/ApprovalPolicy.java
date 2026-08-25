package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface ApprovalPolicy {
    boolean approve(Tool tool, JsonNode arguments);

    /** Non-interactive denial check run for every tool, including normally safe tools. */
    default boolean preflightDeny(Tool tool, JsonNode arguments) {
        return false;
    }
}
