package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface ApprovalPolicy {
    boolean approve(Tool tool, JsonNode arguments);
}
