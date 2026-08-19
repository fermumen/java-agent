package dev.fxjava;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/** Transport contract for the OpenAI Responses API. */
public interface ResponsesClient {
    ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions)
            throws IOException, InterruptedException;
}
