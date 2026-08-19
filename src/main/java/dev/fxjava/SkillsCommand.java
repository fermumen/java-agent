package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Non-model skills management matching fx's list/show/create/remove/local-install surface. */
final class SkillsCommand {
    private SkillsCommand() { }

    static void populate(ObjectNode result, String arguments, Path workspace, Path stateRoot,
                         ObjectMapper json) throws Exception {
        if (!Files.isDirectory(workspace)) throw new IOException("workspace is not a directory: " + workspace);
        SkillManager manager = new SkillManager(stateRoot);
        String rest = arguments.trim();
        int separator = rest.indexOf(' ');
        String action = rest.isBlank() ? "list" : (separator < 0 ? rest : rest.substring(0, separator));
        String value = separator < 0 ? "" : rest.substring(separator + 1).trim();
        result.put("action", action).put("managed_root", manager.root().toString());
        switch (action) {
            case "list" -> list(result, workspace, stateRoot, manager);
            case "path" -> { }
            case "create" -> {
                Path file = manager.create(value);
                result.put("path", file.toString()).put("reload", true);
            }
            case "remove" -> {
                SkillTool.Skill selected = SkillTool.inventory(workspace, stateRoot).stream()
                        .filter(skill -> skill.name().equals(value)).findFirst()
                        .orElseThrow(() -> new IOException("Skill not found: " + value));
                if (!manager.owns(selected.directory())) {
                    throw new IOException("Skill is not in the managed install root: " + value);
                }
                manager.remove(selected.directory().getFileName().toString());
                result.put("removed", value).put("reload", true);
            }
            case "show" -> result.put("name", value).put("content",
                    SkillTool.create(workspace, stateRoot).execute(json.createObjectNode().put("name", value)));
            case "add", "install" -> install(result, value, workspace, stateRoot, json);
            default -> throw new IllegalArgumentException(
                    "Usage: skills [list|add|install|show|create|remove|path] [name|path]");
        }
    }

    private static void list(ObjectNode result, Path workspace, Path stateRoot,
                             SkillManager manager) throws IOException {
        ArrayNode skills = result.putArray("skills");
        for (SkillTool.Skill skill : SkillTool.inventory(workspace, stateRoot)) {
            skills.addObject().put("name", skill.name()).put("description", skill.description())
                    .put("location", skill.directory().toString()).put("managed", manager.owns(skill.directory()));
        }
        result.put("count", skills.size());
    }

    private static void install(ObjectNode result, String value, Path workspace, Path stateRoot,
                                ObjectMapper json) throws Exception {
        String source = value;
        String filter = "";
        int spaced = value.indexOf(" --skill ");
        int equals = value.indexOf(" --skill=");
        int option = spaced >= 0 ? spaced : equals;
        if (option >= 0) {
            source = value.substring(0, option).trim();
            filter = value.substring(option + 9).trim();
        }
        ObjectNode input = json.createObjectNode().put("source", source);
        if (!filter.isBlank()) input.put("skill", filter);
        result.put("status", new InstallSkillTool(workspace, stateRoot).execute(input)).put("reload", true);
    }
}
