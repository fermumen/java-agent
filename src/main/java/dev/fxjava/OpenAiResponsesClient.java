package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Dependency-light OpenAI Responses API client backed by the JDK HTTP client. */
public final class OpenAiResponsesClient implements ResponsesClient {
    static final int MAX_PROVIDER_ATTEMPTS = 10;
    static final long MAX_RETRY_DELAY_MS = 30_000;
    static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DEFAULT_STREAM_OVERALL_TIMEOUT = Duration.ofMinutes(10);
    static final int MAX_STREAM_BODY_BYTES = 16 * 1024 * 1024;
    static final int MAX_STREAM_EVENT_LINE_BYTES = 256 * 1024;
    static final int MAX_STREAM_EVENT_CHARS = 1_000_000;
    static final int MAX_STREAM_EVENTS = 10_000;
    static final int MAX_ERROR_BODY_BYTES = 4_000;
    private static final int STREAM_CHUNK_BYTES = 16 * 1024;
    private static final int MAX_QUEUED_CHUNKS = 128;
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Sleeper sleeper;
    private final Duration streamIdleTimeout;
    private final Duration streamOverallTimeout;

    public OpenAiResponsesClient(ObjectMapper json, AgentConfig config) {
        this(json, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), config);
    }

    OpenAiResponsesClient(ObjectMapper json, HttpClient http, AgentConfig config) {
        this(json, http, config, Thread::sleep);
    }

    OpenAiResponsesClient(ObjectMapper json, HttpClient http, AgentConfig config, Sleeper sleeper) {
        this(json, http, config, sleeper, DEFAULT_STREAM_IDLE_TIMEOUT, DEFAULT_STREAM_OVERALL_TIMEOUT);
    }

    OpenAiResponsesClient(ObjectMapper json, HttpClient http, AgentConfig config, Sleeper sleeper,
                          Duration streamIdleTimeout, Duration streamOverallTimeout) {
        this.json = json;
        this.http = http;
        this.endpoint = responsesEndpoint(config.baseUrl());
        this.apiKey = config.apiKey();
        this.model = config.model();
        this.sleeper = sleeper;
        this.streamIdleTimeout = positive(streamIdleTimeout, "streamIdleTimeout");
        this.streamOverallTimeout = positive(streamOverallTimeout, "streamOverallTimeout");
    }

    @Override
    public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions)
            throws IOException, InterruptedException {
        ObjectNode body = requestBody(input, tools, instructions, false);
        RetryPacing pacing = new RetryPacing();
        for (int attempt = 1; attempt <= MAX_PROVIDER_ATTEMPTS; attempt++) {
            HttpResponse<String> response = http.send(request(body, "application/json"),
                    HttpResponse.BodyHandlers.ofString());
            if (shouldRetry(response.statusCode(), attempt)) {
                sleepBeforeRetry(response.statusCode(), response.headers(), pacing);
                continue;
            }
            requireSuccess(response.statusCode(), response.body());
            JsonNode parsed = json.readTree(response.body());
            if (!parsed.isObject()) throw new IOException("OpenAI returned a non-object response");
            return validate((ObjectNode) parsed);
        }
        throw new IllegalStateException("unreachable provider retry loop");
    }

    @Override
    public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions,
                               Consumer<String> textDelta)
            throws IOException, InterruptedException {
        ObjectNode body = requestBody(input, tools, instructions, true);
        RetryPacing pacing = new RetryPacing();
        for (int attempt = 1; attempt <= MAX_PROVIDER_ATTEMPTS; attempt++) {
            HttpResponse<StreamingBody> response = http.send(request(body, "text/event-stream"),
                    info -> new StreamingBodySubscriber(streamIdleTimeout, streamOverallTimeout));
            try (StreamingBody stream = response.body()) {
                if (shouldRetry(response.statusCode(), attempt)) {
                    stream.cancel();
                    sleepBeforeRetry(response.statusCode(), response.headers(), pacing);
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String message = stream.readErrorBody(MAX_ERROR_BODY_BYTES);
                    requireSuccess(response.statusCode(), message);
                }
                return validate(parseStreamingEventStream(json, stream, textDelta));
            }
        }
        throw new IllegalStateException("unreachable provider retry loop");
    }

    static ObjectNode parseEventStream(ObjectMapper json, Iterable<String> lines,
                                       Consumer<String> textDelta) throws IOException {
        SseEventParser parser = new SseEventParser(json, textDelta);
        for (String line : lines) {
            if (line.length() > MAX_STREAM_EVENT_LINE_BYTES) {
                throw new IOException("OpenAI stream event line exceeded the size limit");
            }
            ObjectNode completed = parser.accept(line);
            if (completed != null) return completed;
        }
        return parser.finish();
    }

    private static ObjectNode parseStreamingEventStream(ObjectMapper json, StreamingBody stream,
                                                        Consumer<String> textDelta)
            throws IOException, InterruptedException {
        SseEventParser parser = new SseEventParser(json, textDelta);
        for (;;) {
            String line = stream.readLine(MAX_STREAM_EVENT_LINE_BYTES);
            if (line == null) return parser.finish();
            ObjectNode completed = parser.accept(line);
            if (completed != null) return completed;
        }
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static ObjectNode processEvent(ObjectMapper json, StringBuilder data,
                                           Consumer<String> textDelta) throws IOException {
        if (data.length() == 0 || data.toString().equals("[DONE]")) return null;
        JsonNode event = json.readTree(data.toString());
        String type = event.path("type").asText();
        if (type.equals("response.output_text.delta")) {
            String delta = event.path("delta").asText();
            if (!delta.isEmpty()) textDelta.accept(delta);
            return null;
        }
        if (type.equals("response.completed")) {
            JsonNode response = event.path("response");
            if (!response.isObject()) throw new IOException("response.completed omitted its response object");
            return (ObjectNode) response;
        }
        if (type.equals("error") || type.equals("response.failed") || type.equals("response.incomplete")) {
            JsonNode response = event.path("response");
            String message = firstNonBlank(event.path("message").asText(),
                    event.path("error").path("message").asText(),
                    response.path("error").path("message").asText(),
                    response.path("incomplete_details").path("reason").asText());
            throw new IOException("OpenAI stream " + type + (message == null ? "" : ": " + message));
        }
        return null;
    }

    private ObjectNode requestBody(ArrayNode input, ArrayNode tools, String instructions, boolean stream) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("instructions", instructions);
        body.set("input", input);
        body.set("tools", tools);
        body.put("tool_choice", "auto");
        body.put("store", false);
        body.put("stream", stream);
        body.putArray("include").add("reasoning.encrypted_content");
        return body;
    }

    private HttpRequest request(ObjectNode body, String accept) throws IOException {
        return HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
    }

    private static ObjectNode validate(ObjectNode result) throws IOException {
        String status = result.path("status").asText("completed");
        if (!status.equals("completed")) {
            String detail = firstNonBlank(result.path("error").path("message").asText(),
                    result.path("incomplete_details").path("reason").asText());
            throw new IOException("OpenAI response status was " + status
                    + (detail == null ? "" : ": " + detail));
        }
        if (!result.path("output").isArray()) {
            throw new IOException("OpenAI response did not contain an output array");
        }
        return result;
    }

    private static void requireSuccess(int statusCode, String body) throws IOException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("OpenAI returned HTTP " + statusCode + ": " + abbreviate(body, 4_000));
        }
    }

    static boolean retryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    static long retryDelayMillis(String retryAfter, int retryAttempt) {
        Long explicit = retryAfterMillis(retryAfter);
        if (explicit != null) return explicit;
        if (retryAttempt <= 0) return 0;
        if (retryAttempt == 1) return 250;
        long seconds = 1L << Math.min(retryAttempt - 2, 30);
        return Math.min(seconds * 1_000, MAX_RETRY_DELAY_MS);
    }

    private static boolean shouldRetry(int status, int attempt) {
        return retryableStatus(status) && attempt < MAX_PROVIDER_ATTEMPTS;
    }

    private void sleepBeforeRetry(int status, HttpHeaders headers, RetryPacing pacing) throws InterruptedException {
        sleeper.sleep(pacing.next(status, headers.firstValue("Retry-After").orElse(null)));
    }

    private static Long retryAfterMillis(String retryAfter) {
        if (retryAfter == null) return null;
        String value = retryAfter.trim();
        if (value.isEmpty() || !value.chars().allMatch(character -> character >= '0' && character <= '9')) {
            return null;
        }
        long seconds = 0;
        for (int index = 0; index < value.length(); index++) {
            seconds = seconds * 10 + value.charAt(index) - '0';
            if (seconds >= 30) return MAX_RETRY_DELAY_MS;
        }
        return seconds * 1_000;
    }

    /**
     * fx responses_protocol.parseUsage: reads input_tokens/output_tokens off a
     * completed response; absent, non-numeric, fractional, or negative fields
     * count as zero. Returns {@code {input, output}}.
     */
    static long[] parseUsage(JsonNode response) {
        JsonNode usage = response.path("usage");
        if (!usage.isObject()) return new long[]{0, 0};
        return new long[]{nonNegativeTokens(usage.get("input_tokens")),
                nonNegativeTokens(usage.get("output_tokens"))};
    }

    private static long nonNegativeTokens(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0) {
            return 0;
        }
        return value.longValue();
    }

    static URI responsesEndpoint(String value) {
        String base = value.replaceAll("/+$", "");
        return URI.create(base.endsWith("/responses") ? base : base + "/responses");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    @FunctionalInterface
    interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }

    static final class RetryPacing {
        private int cause;
        private int attempt;

        long next(int status, String retryAfter) {
            Long explicit = retryAfterMillis(retryAfter);
            if (explicit != null) {
                cause = 0;
                attempt = 0;
                return explicit;
            }
            int nextCause = status == 429 ? 1 : 2;
            attempt = cause == nextCause ? attempt + 1 : 1;
            cause = nextCause;
            return retryDelayMillis(null, attempt);
        }
    }

    private static final class SseEventParser {
        private final ObjectMapper json;
        private final Consumer<String> textDelta;
        private final StringBuilder data = new StringBuilder();
        private boolean hasData;
        private boolean firstLine = true;
        private int events;

        SseEventParser(ObjectMapper json, Consumer<String> textDelta) {
            this.json = json;
            this.textDelta = textDelta;
        }

        ObjectNode accept(String rawLine) throws IOException {
            String line = rawLine;
            if (firstLine) {
                firstLine = false;
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
            }
            if (line.isEmpty()) {
                if (++events > MAX_STREAM_EVENTS) {
                    throw new IOException("OpenAI stream exceeded the event count limit");
                }
                ObjectNode completed = hasData ? processEvent(json, data, textDelta) : null;
                data.setLength(0);
                hasData = false;
                return completed;
            }
            if (line.charAt(0) == ':') return null;
            int separator = line.indexOf(':');
            String field = separator < 0 ? line : line.substring(0, separator);
            if (!field.equals("data")) return null;
            String value = separator < 0 ? "" : line.substring(separator + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            int nextLength = data.length() + (hasData ? 1 : 0) + value.length();
            if (nextLength > MAX_STREAM_EVENT_CHARS) {
                throw new IOException("OpenAI stream event exceeded the size limit");
            }
            if (hasData) data.append('\n');
            data.append(value);
            hasData = true;
            return null;
        }

        ObjectNode finish() throws IOException {
            if (hasData) {
                if (++events > MAX_STREAM_EVENTS) {
                    throw new IOException("OpenAI stream exceeded the event count limit");
                }
                ObjectNode completed = processEvent(json, data, textDelta);
                data.setLength(0);
                hasData = false;
                if (completed != null) return completed;
            }
            throw new IOException("OpenAI stream ended without response.completed");
        }
    }

    private static final class StreamingBodySubscriber implements HttpResponse.BodySubscriber<StreamingBody> {
        private final CompletableFuture<StreamingBody> body = new CompletableFuture<>();
        private final StreamingBody stream;
        private Flow.Subscription subscription;

        StreamingBodySubscriber(Duration idleTimeout, Duration overallTimeout) {
            this.stream = new StreamingBody(idleTimeout, overallTimeout);
        }

        @Override
        public CompletionStage<StreamingBody> getBody() {
            return body;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) {
                incoming.cancel();
                return;
            }
            subscription = incoming;
            stream.attach(incoming);
            body.complete(stream);
            incoming.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            try {
                for (ByteBuffer item : items) stream.accept(item);
                Flow.Subscription current;
                synchronized (this) { current = subscription; }
                if (current != null && !stream.isClosed()) current.request(1);
            } catch (IOException failure) {
                stream.fail(failure);
            }
        }

        @Override
        public void onError(Throwable failure) {
            IOException error = asIOException("OpenAI response stream failed", failure);
            if (!body.isDone()) body.completeExceptionally(error);
            stream.fail(error);
        }

        @Override
        public void onComplete() {
            stream.complete();
        }
    }

    private static IOException asIOException(String message, Throwable cause) {
        if (cause instanceof IOException) return (IOException) cause;
        return new IOException(message, cause);
    }

    private static final class StreamingBody implements AutoCloseable {
        private final BlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(MAX_QUEUED_CHUNKS);
        private final long idleTimeoutNanos;
        private final long overallTimeoutNanos;
        private final long startedAt = System.nanoTime();
        private volatile long lastNetworkActivity = startedAt;
        private volatile Flow.Subscription subscription;
        private volatile boolean finished;
        private volatile boolean closed;
        private volatile IOException failure;
        private long totalBytes;
        private byte[] current;
        private int currentIndex;
        private boolean skipOptionalLf;

        StreamingBody(Duration idleTimeout, Duration overallTimeout) {
            idleTimeoutNanos = idleTimeout.toNanos();
            overallTimeoutNanos = overallTimeout.toNanos();
        }

        void attach(Flow.Subscription value) {
            subscription = value;
            if (closed) value.cancel();
        }

        boolean isClosed() { return closed; }

        void accept(ByteBuffer source) throws IOException {
            if (closed) return;
            ByteBuffer input = source.duplicate();
            int count = input.remaining();
            synchronized (this) {
                if (count > MAX_STREAM_BODY_BYTES - totalBytes) {
                    throw new IOException("OpenAI stream exceeded the body size limit");
                }
                totalBytes += count;
            }
            lastNetworkActivity = System.nanoTime();
            while (input.hasRemaining()) {
                if (closed) return;
                int length = Math.min(STREAM_CHUNK_BYTES, input.remaining());
                byte[] copy = new byte[length];
                input.get(copy);
                try {
                    while (!chunks.offer(copy, 100, TimeUnit.MILLISECONDS)) {
                        if (closed) return;
                        if (expiredOverall()) {
                            throw new SocketTimeoutException("OpenAI stream exceeded its overall timeout");
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException io = new InterruptedIOException("Interrupted while receiving OpenAI stream");
                    io.initCause(interrupted);
                    throw io;
                }
            }
        }

        String readLine(int maxBytes) throws IOException, InterruptedException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maxBytes, 1024));
            boolean sawByte = false;
            for (;;) {
                int next = readByte();
                if (next < 0) {
                    if (!sawByte) return null;
                    return decodeLine(line.toByteArray());
                }
                if (skipOptionalLf) {
                    skipOptionalLf = false;
                    if (next == '\n') continue;
                }
                if (next == '\r') {
                    skipOptionalLf = true;
                    return decodeLine(line.toByteArray());
                }
                if (next == '\n') return decodeLine(line.toByteArray());
                sawByte = true;
                if (line.size() >= maxBytes) {
                    throw new IOException("OpenAI stream event line exceeded the size limit");
                }
                line.write(next);
            }
        }

        String readErrorBody(int maxBytes) throws IOException, InterruptedException {
            ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(maxBytes, 1024));
            while (result.size() < maxBytes) {
                int value = readByte();
                if (value < 0) break;
                result.write(value);
            }
            return new String(result.toByteArray(), StandardCharsets.UTF_8);
        }

        private static String decodeLine(byte[] value) throws IOException {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(value)).toString();
            } catch (CharacterCodingException malformed) {
                throw new IOException("OpenAI stream contained invalid UTF-8", malformed);
            }
        }

        private int readByte() throws IOException, InterruptedException {
            if (Thread.currentThread().isInterrupted()) {
                cancel();
                throw new InterruptedException("Interrupted while reading OpenAI stream");
            }
            for (;;) {
                if (expiredOverall()) {
                    SocketTimeoutException timeout = new SocketTimeoutException("OpenAI stream exceeded its overall timeout");
                    cancelWithFailure(timeout);
                    throw timeout;
                }
                if (current != null && currentIndex < current.length) return current[currentIndex++] & 0xff;
                current = null;
                currentIndex = 0;
                byte[] ready = chunks.poll();
                if (ready != null) {
                    current = ready;
                    continue;
                }
                IOException failed = failure;
                if (failed != null) throw failed;
                if (finished || closed) return -1;
                long now = System.nanoTime();
                long overallLeft = overallTimeoutNanos - (now - startedAt);
                long idleLeft = idleTimeoutNanos - (now - lastNetworkActivity);
                if (idleLeft <= 0) {
                    SocketTimeoutException timeout = new SocketTimeoutException("OpenAI stream exceeded its idle timeout");
                    cancelWithFailure(timeout);
                    throw timeout;
                }
                byte[] next;
                try {
                    next = chunks.poll(Math.min(Math.min(idleLeft, overallLeft), TimeUnit.MILLISECONDS.toNanos(100)),
                            TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    cancel();
                    throw interrupted;
                }
                if (next != null) {
                    current = next;
                    continue;
                }
            }
        }

        synchronized void fail(IOException error) {
            if (finished || closed) return;
            failure = error;
            finished = true;
            Flow.Subscription currentSubscription = subscription;
            if (currentSubscription != null) currentSubscription.cancel();
        }

        void complete() {
            finished = true;
        }

        private boolean expiredOverall() {
            return System.nanoTime() - startedAt >= overallTimeoutNanos;
        }

        private void cancelWithFailure(IOException error) {
            fail(error);
            close();
        }

        void cancel() {
            close();
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            Flow.Subscription currentSubscription = subscription;
            if (currentSubscription != null) currentSubscription.cancel();
            chunks.clear();
        }
    }
}
