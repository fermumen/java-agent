package dev.fxjava;

import java.io.IOException;
import java.util.List;

/** Supplies tools whose names can change while an agent session is running. */
interface DynamicToolProvider {
    Tool resolveDynamicTool(String name) throws IOException;

    List<Tool> dynamicTools() throws IOException;
}
