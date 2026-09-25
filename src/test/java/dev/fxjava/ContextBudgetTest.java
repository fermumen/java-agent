package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBudgetTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void imagePayloadBytesAreExcludedAndChargedAtTheConfiguredReserve() {
        ContextBudget budget = new ContextBudget(10_000, 80, 1_234);
        ArrayNode tiny = historyWithImage("data:image/png;base64,A");
        ArrayNode huge = historyWithImage("data:image/png;base64," + "A".repeat(2_000_000));

        assertEquals(budget.estimateHistoryTokens(tiny), budget.estimateHistoryTokens(huge));
        assertTrue(budget.estimateHistoryTokens(tiny) >= 1_234);
        assertFalse(budget.shouldCompact(tiny));
        assertTrue(budget.shouldCompact(tiny, budget.triggerTokenBudget()));
    }

    @Test
    void fixedPromptAndToolOverheadReducesTheAvailableHistoryBudget() {
        ContextBudget budget = new ContextBudget(1_000, 80, 0);
        ArrayNode history = json.createArrayNode();
        history.addObject().put("role", "user").put("content", "small request");
        long historyTokens = budget.estimateHistoryTokens(history);
        long trigger = budget.triggerTokenBudget();

        assertEquals(trigger, budget.historyBudget(0) + 0);
        assertEquals(trigger - 100, budget.historyBudget(100));
        assertFalse(budget.shouldCompact(history, trigger - historyTokens - 1));
        assertTrue(budget.shouldCompact(history, trigger - historyTokens));
    }

    private ArrayNode historyWithImage(String dataUrl) {
        ArrayNode history = json.createArrayNode();
        history.addObject().put("role", "user").putArray("content")
                .addObject().put("type", "input_image").put("image_url", dataUrl).put("detail", "auto");
        return history;
    }
}
