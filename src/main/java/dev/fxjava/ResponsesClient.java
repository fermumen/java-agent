package dev.fxjava;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.function.Consumer;

/** Transport contract for the OpenAI Responses API. */
public interface ResponsesClient {
    ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions)
            throws IOException, InterruptedException;

    default ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions,
                                Consumer<String> textDelta)
            throws IOException, InterruptedException {
        return complete(input, tools, instructions);
    }
}
