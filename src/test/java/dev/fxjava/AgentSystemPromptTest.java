package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSystemPromptTest {
    @Test
    void advertisesPortableProductivityScriptWorkflow() {
        AgentConfig config = new AgentConfig("key", "https://example.test/v1", "model",
                Path.of("workspace").toAbsolutePath(), 10, PermissionMode.ASK);

        String prompt = Agent.defaultSystemPrompt(config);

        assertTrue(prompt.contains("productivity.jar"));
        assertTrue(prompt.contains("jshell --class-path"));
        assertTrue(prompt.contains("java --class-path"));
        assertTrue(prompt.contains("Windows"));
        assertTrue(prompt.contains("Linux"));
        assertTrue(prompt.contains("Verify that file exists"));
        assertTrue(prompt.contains("Do not download dependencies at runtime"));
        assertTrue(prompt.contains("Apache POI: XLS/XLSX, DOCX, and PPTX"));
        assertTrue(prompt.contains("PDFBox: PDF reading"));
        assertTrue(prompt.contains("Tika Core: file-type detection"));
        assertTrue(prompt.contains("Commons CSV:"));
        assertTrue(prompt.contains("Jackson: JSON and YAML"));
        assertTrue(prompt.contains("jsoup: HTML/XML"));
        assertTrue(prompt.contains("commonmark-java: Markdown"));
        assertTrue(prompt.contains("Commons IO:"));
        assertTrue(prompt.contains("Commons Compress and XZ:"));
        assertTrue(prompt.contains("Commons Lang, Text, and Codec:"));
        assertTrue(prompt.contains("Commons Math:"));
        assertTrue(prompt.contains("TwelveMonkeys ImageIO:"));
        assertTrue(prompt.contains("XChart:"));
    }
}
