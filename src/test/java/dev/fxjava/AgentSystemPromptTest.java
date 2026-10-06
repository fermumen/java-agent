package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSystemPromptTest {
    @Test
    void pointsProductivityWorkToTheBeanShellToolOnAMinimalHost() {
        AgentConfig config = new AgentConfig("key", "https://example.test/v1", "model",
                Path.of("workspace").toAbsolutePath(), 10, PermissionMode.ASK);

        String prompt = Agent.defaultSystemPrompt(config);

        assertTrue(prompt.contains("productivity.jar"));
        assertTrue(prompt.contains("use the beanshell tool; its description lists the\nBeanShell syntax limits"));
        assertTrue(prompt.contains("jshell, javac, and `java Script.java` are unavailable"));
        assertTrue(prompt.contains("no git, rg, grep, curl, or Python"));
        assertTrue(prompt.contains("restricted PowerShell"));
        assertTrue(prompt.contains("Prefer the file tools over shell commands"));
        assertFalse(prompt.contains("No generics"), "syntax limits live in the beanshell tool description");
        assertFalse(prompt.contains("jshell --class-path"));
        assertFalse(prompt.contains("java --class-path"),
                "the target is a plain JRE, so source-file launch must not be advertised");
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
        assertTrue(prompt.contains("JDBC: Oracle Thin and Microsoft SQL Server"));
        assertTrue(prompt.contains("no native-login DLL is bundled"));
        assertTrue(prompt.contains("do not assume Windows integrated login is available"));
    }
}
