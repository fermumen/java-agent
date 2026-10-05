package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclEntry.Builder;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Small, private, opt-in user settings file; never print this object or its contents. */
final class UserPreferences {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_BYTES = 16 * 1024;
    private static final Set<PosixFilePermission> PRIVATE_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<AclEntryPermission> PRIVATE_ACL = EnumSet.of(
            AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
            AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.DELETE, AclEntryPermission.READ_ACL, AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER, AclEntryPermission.SYNCHRONIZE);

    private final Path root;
    private final String apiKey;
    private final String model;
    private final String reasoningEffort;
    private final String baseUrl;

    private UserPreferences(Path root, String apiKey, String model, String reasoningEffort, String baseUrl) {
        this.root = root.toAbsolutePath().normalize();
        this.apiKey = apiKey;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
        this.baseUrl = baseUrl;
    }

    static UserPreferences empty(Path root) {
        return new UserPreferences(root, null, null, null, null);
    }

    static UserPreferences load(Path root) throws IOException {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path file = absoluteRoot.resolve("user-settings.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new UserPreferences(absoluteRoot, null, null, null, null);
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("settings file is not a regular file");
        }
        requirePrivate(file);
        long size = Files.size(file);
        if (size > MAX_BYTES) throw new IOException("settings file is too large");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException unreadable) {
            throw new IOException("settings file could not be read securely");
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(bytes);
        } catch (IOException | RuntimeException malformed) {
            throw new IOException("settings file is invalid");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        if (parsed == null || !parsed.isObject()) throw new IOException("settings file is invalid");
        return new UserPreferences(absoluteRoot,
                boundedText(parsed, "api_key", 8192),
                boundedText(parsed, "model", 256),
                boundedText(parsed, "reasoning_effort", 64),
                boundedText(parsed, "base_url", 2048));
    }

    String apiKey() { return apiKey; }
    String model() { return model; }
    String reasoningEffort() { return reasoningEffort; }
    String baseUrl() { return baseUrl; }
    Path file() { return root.resolve("user-settings.json"); }

    UserPreferences withApiKey(String value) {
        return new UserPreferences(root, nonBlank(value), model, reasoningEffort, baseUrl);
    }

    UserPreferences withModel(String value) {
        return new UserPreferences(root, apiKey, nonBlank(value), reasoningEffort, baseUrl);
    }

    UserPreferences withReasoningEffort(String value) {
        return new UserPreferences(root, apiKey, model, nonBlank(value), baseUrl);
    }

    UserPreferences withBaseUrl(String value) {
        return new UserPreferences(root, apiKey, model, reasoningEffort, nonBlank(value));
    }

    void save() throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("settings directory is a symbolic link");
        Path target = file();
        ObjectNode document = JSON.createObjectNode();
        if (apiKey != null) document.put("api_key", apiKey);
        if (model != null) document.put("model", model);
        if (reasoningEffort != null) document.put("reasoning_effort", reasoningEffort);
        if (baseUrl != null) document.put("base_url", baseUrl);
        byte[] contents = JSON.writeValueAsBytes(document);

        Path temporary = null;
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(root, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                FileAttribute<Set<PosixFilePermission>> permissions = PosixFilePermissions.asFileAttribute(PRIVATE_FILE);
                temporary = Files.createTempFile(root, ".user-settings-", ".tmp", permissions);
            } else {
                temporary = Files.createTempFile(root, ".user-settings-", ".tmp");
            }
            requirePrivate(temporary);
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(contents);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            requirePrivate(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            try {
                requirePrivate(target);
            } catch (IOException insecure) {
                Files.deleteIfExists(target);
                throw insecure;
            }
        } finally {
            Arrays.fill(contents, (byte) 0);
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    private static String boundedText(JsonNode node, String field, int maximum) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().length() > maximum) {
            throw new IOException("settings file is invalid");
        }
        return nonBlank(value.asText());
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Tightens existing settings before reading or writing any credential bytes. */
    private static void requirePrivate(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.setPosixFilePermissions(file, PRIVATE_FILE);
            Set<PosixFilePermission> current = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (!current.equals(PRIVATE_FILE)) throw new IOException("private file permissions could not be enforced");
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("private settings permissions are unsupported on this filesystem");
        UserPrincipal owner = acl.getOwner();
        Builder ownerEntry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(PRIVATE_ACL);
        acl.setAcl(List.of(ownerEntry.build()));
        for (AclEntry entry : acl.getAcl()) {
            if (entry.type() != AclEntryType.ALLOW || !entry.principal().equals(owner)) {
                throw new IOException("private file permissions could not be enforced");
            }
        }
    }
}
