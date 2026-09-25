package dev.fxjava;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Shared /image command core for the raw shell and the legacy fallback loop,
 * ported from fx's image_commands.zig: {@code /image <path>} loads and stages
 * a bounded local attachment for the next submitted message, bare {@code /image}
 * lists pending attachments, and {@code /image clear} discards them. Failures
 * print one friendly line and leave both composer and queue untouched. Output
 * is dim through {@link Ansi} on TTY paths and byte-identical plain text
 * otherwise.
 */
final class ImageCommands {
    static final String USAGE = "Usage: /image <path> (or /image clear, or bare /image to list)";

    private ImageCommands() {
    }

    /** Handles one /image invocation; {@code argument} is the trimmed remainder. */
    static void handle(SessionRuntime session, String argument, Path workspace,
                       Ansi ansi, PrintStream out) {
        String rest = argument == null ? "" : argument.strip();
        if (rest.equalsIgnoreCase("clear")) {
            out.println(ansi.dim() + (session.clearPendingImages()
                    ? "cleared pending images" : "no pending images") + ansi.reset());
            return;
        }
        if (rest.isEmpty()) {
            listPending(session, ansi, out);
            return;
        }
        try {
            ImageAttachment image = ImageAttachment.load(workspace, rest);
            session.stagePendingImage(image);
            out.println(ansi.dim() + "attached image: " + describe(image) + ansi.reset());
        } catch (IOException | IllegalArgumentException failed) {
            out.println(ansi.dim() + rejection(failed) + ansi.reset());
        }
    }

    /** fx-shaped listing: a count header plus one media-typed line per image. */
    private static void listPending(SessionRuntime session, Ansi ansi, PrintStream out) {
        List<ImageAttachment> pending = session.pendingImages();
        if (pending.isEmpty()) {
            out.println(ansi.dim() + "no pending images" + ansi.reset());
            return;
        }
        out.println(ansi.dim() + pending.size() + " pending" + ansi.reset());
        for (ImageAttachment image : pending) {
            out.println(ansi.dim() + " - " + image.path().getFileName() + " ("
                    + image.mediaType() + ")" + ansi.reset());
        }
    }

    /** Friendly one-line reason an attachment was refused; never a stack trace. */
    private static String rejection(Exception failed) {
        String message = failed.getMessage() == null || failed.getMessage().isBlank()
                ? failed.getClass().getSimpleName() : failed.getMessage();
        if (failed instanceof IllegalArgumentException && message.contains("blank")) return USAGE;
        if (failed instanceof ImageAttachment.ImageNotFoundException) return "image file not found: " + message;
        if (failed instanceof ImageAttachment.ImageTooLargeException
                || failed instanceof ImageAttachment.UnsupportedImageTypeException) return message;
        return "failed to attach image: " + message;
    }

    /** Dim composer-frame fragment, e.g. "[img] photo.png · 1.2 MiB". */
    static String pendingLine(ImageAttachment image) {
        return "[img] " + describe(image);
    }

    /** Turn-start summary, e.g. "2 images attached: photo.png, chart.jpeg". */
    static String attachmentSummary(List<ImageAttachment> images) {
        StringBuilder names = new StringBuilder();
        for (ImageAttachment image : images) {
            if (names.length() > 0) names.append(", ");
            names.append(image.path().getFileName());
        }
        return (images.size() == 1 ? "1 image attached: " : images.size() + " images attached: ")
                + names;
    }

    /** Basename plus human-readable size, e.g. "photo.png · 1.2 MiB". */
    static String describe(ImageAttachment image) {
        return image.path().getFileName() + " · " + humanBytes(image.bytes().length);
    }

    static String humanBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double kibibytes = bytes / 1024.0;
        if (kibibytes < 1024) return String.format(Locale.ROOT, "%.1f KiB", kibibytes);
        return String.format(Locale.ROOT, "%.1f MiB", kibibytes / 1024.0);
    }
}
