package com.niranjankagri.aitesting.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * The single place where this project talks to Claude.
 *
 * <p>Two modes, chosen by the environment:
 * <ul>
 *   <li><b>live</b> (ANTHROPIC_API_KEY is set): calls the Messages API and saves the answer as a
 *       recording under ai/recordings/, so the same question can be answered later without a key.</li>
 *   <li><b>replay</b> (no key, or AI_MODE=replay): answers only from recordings. CI runs this way,
 *       so the pipeline is tested on every push for free and gives the same result every time.</li>
 * </ul>
 * Every answer is a typed Java record: the SDK derives a JSON schema from the record class and the
 * API's structured outputs guarantee a reply that matches it, so no fragile text parsing.
 */
public final class AiGateway {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    public static final Path RECORDINGS = Path.of("ai", "recordings");
    private static final Path USAGE_LOG = Path.of("target", "ai-usage.csv");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Part of a message: plain text or a PNG screenshot. */
    public sealed interface Part permits Text, Png {
    }

    public record Text(String text) implements Part {
    }

    public record Png(byte[] bytes) implements Part {
    }

    /** One turn of the conversation. Assistant turns are earlier answers (for repair rounds). */
    public record Turn(boolean fromUser, List<Part> parts) {

        public static Turn user(Part... parts) {
            return new Turn(true, List.of(parts));
        }

        public static Turn assistant(String text) {
            return new Turn(false, List.of(new Text(text)));
        }
    }

    /**
     * One question to Claude.
     *
     * @param purpose     folder name under ai/recordings (generate, triage)
     * @param label       readable part of the recording file name (a test case id, a test name)
     * @param keyMaterial everything that should make a recording stale when it changes (prompt,
     *                    test case text, code sent as context). Volatile values such as ports or
     *                    timings must be left out, or replay could never find the recording again.
     * @param system      system prompt blocks; the last one gets a cache breakpoint
     * @param turns       the conversation, starting with a user turn
     * @param effort      thinking depth (Claude Opus 5.5 defaults to medium)
     * @param answerType  record class the answer must match
     */
    public record Request<T>(String purpose, String label, String keyMaterial, List<String> system,
                             List<Turn> turns, OutputConfig.Effort effort, Class<T> answerType) {
    }

    /** The typed answer, plus whether it came from a recording and what it cost. */
    public record Answer<T>(T value, boolean replayed, String usageLine) {
    }

    /** Claude declined, ran out of tokens, or replay had no recording: the caller stops with this message. */
    public static final class AiException extends RuntimeException {
        public AiException(String message) {
            super(message);
        }
    }

    private final String model;
    private final boolean live;
    private AnthropicClient client;

    private AiGateway(String model, boolean live) {
        this.model = model;
        this.live = live;
    }

    /** Mode and model from the environment: AI_MODE (live|replay), AI_MODEL, ANTHROPIC_API_KEY. */
    public static AiGateway fromEnvironment() {
        String mode = System.getenv().getOrDefault("AI_MODE", "");
        boolean hasKey = !System.getenv().getOrDefault("ANTHROPIC_API_KEY", "").isBlank();
        boolean live = mode.isBlank() ? hasKey : mode.equalsIgnoreCase("live");
        return new AiGateway(System.getenv().getOrDefault("AI_MODEL", DEFAULT_MODEL), live);
    }

    /** Replay-only gateway for unit tests. */
    public static AiGateway replayOnly(String model) {
        return new AiGateway(model, false);
    }

    public String model() {
        return model;
    }

    public boolean isLive() {
        return live;
    }

    /** Asks the question live (and records it) or answers it from the recording. */
    public <T> Answer<T> ask(Request<T> request) {
        Path recording = recordingFile(request);
        if (!live) {
            return replay(request, recording);
        }
        StructuredMessage<T> message = client().messages().create(toParams(request));
        T value = checkedAnswer(message, request);
        String usage = usageLine(message.usage());
        save(recording, request, value, message.usage());
        logUsage(request, message.usage());
        return new Answer<>(value, false, usage);
    }

    /** ai/recordings/&lt;purpose&gt;/&lt;label&gt;-&lt;first 12 hex chars of SHA-256(model + key material)&gt;.json */
    public <T> Path recordingFile(Request<T> request) {
        String hash = sha256(model + "\n" + request.keyMaterial()).substring(0, 12);
        return RECORDINGS.resolve(request.purpose()).resolve(request.label() + "-" + hash + ".json");
    }

    private <T> StructuredMessageCreateParams<T> toParams(Request<T> request) {
        List<TextBlockParam> system = new ArrayList<>();
        for (int i = 0; i < request.system().size(); i++) {
            TextBlockParam.Builder block = TextBlockParam.builder().text(request.system().get(i));
            // The system prompt and the framework code are the same for every test case: caching them
            // makes the second and later calls of a run much cheaper.
            if (i == request.system().size() - 1) {
                block.cacheControl(CacheControlEphemeral.builder().build());
            }
            system.add(block.build());
        }
        StructuredMessageCreateParams.Builder<T> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(32_000L)
                .systemOfTextBlockParams(system)
                .outputConfig(StructuredOutputConfig.<T>builder()
                        .effort(request.effort())
                        .format(request.answerType(), JsonSchemaLocalValidation.YES)
                        .build())
                // Server-side refusal fallback: if a safety classifier declines, the API retries on a
                // fallback model inside the same call instead of returning an empty answer.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        for (Turn turn : request.turns()) {
            List<ContentBlockParam> blocks = turn.parts().stream().map(AiGateway::toBlock).toList();
            if (turn.fromUser()) {
                params.addUserMessageOfBlockParams(blocks);
            } else {
                params.addAssistantMessageOfBlockParams(blocks);
            }
        }
        return params.build();
    }

    private static ContentBlockParam toBlock(Part part) {
        return switch (part) {
            case Text t -> ContentBlockParam.ofText(TextBlockParam.builder().text(t.text()).build());
            case Png p -> ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                            .data(Base64.getEncoder().encodeToString(p.bytes()))
                            .build())
                    .build());
        };
    }

    /** Refusal and truncation are reported, never turned into a half answer. */
    private static <T> T checkedAnswer(StructuredMessage<T> message, Request<T> request) {
        StopReason stop = message.stopReason().orElse(StopReason.END_TURN);
        if (stop.equals(StopReason.REFUSAL)) {
            String why = message.stopDetails().map(d -> d.explanation().orElse("no explanation")).orElse("no details");
            throw new AiException("Claude declined the " + request.purpose() + " request for " + request.label()
                    + ": " + why);
        }
        if (stop.equals(StopReason.MAX_TOKENS)) {
            throw new AiException("The answer for " + request.label() + " hit the token limit and was cut off");
        }
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(StructuredTextBlock::text)
                .findFirst()
                .orElseThrow(() -> new AiException("Claude returned no answer for " + request.label()));
    }

    private <T> Answer<T> replay(Request<T> request, Path recording) {
        if (!Files.exists(recording)) {
            throw new AiException("No recording for " + request.purpose() + " " + request.label() + " (" + recording
                    + "). The prompt or its inputs changed since it was recorded: set ANTHROPIC_API_KEY and run the"
                    + " command again to record a fresh answer.");
        }
        try {
            JsonNode saved = JSON.readTree(recording.toFile());
            T value = JSON.treeToValue(saved.get("answer"), request.answerType());
            return new Answer<>(value, true, "replayed " + recording.toString().replace('\\', '/'));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> void save(Path recording, Request<T> request, T value, Usage usage) {
        ObjectNode root = JSON.createObjectNode();
        root.put("purpose", request.purpose());
        root.put("label", request.label());
        root.put("model", model);
        root.put("recordedOn", LocalDate.now().toString());
        root.put("inputTokens", usage.inputTokens());
        root.put("cacheReadTokens", usage.cacheReadInputTokens().orElse(0L));
        root.put("outputTokens", usage.outputTokens());
        root.set("answer", JSON.valueToTree(value));
        try {
            Files.createDirectories(recording.getParent());
            JSON.writeValue(recording.toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> void logUsage(Request<T> request, Usage usage) {
        try {
            Files.createDirectories(USAGE_LOG.getParent());
            if (!Files.exists(USAGE_LOG)) {
                Files.writeString(USAGE_LOG, "purpose,label,model,input,cache_write,cache_read,output,usd\n");
            }
            Files.writeString(USAGE_LOG, String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%d,%.4f%n",
                    request.purpose(), request.label(), model, usage.inputTokens(),
                    usage.cacheCreationInputTokens().orElse(0L), usage.cacheReadInputTokens().orElse(0L),
                    usage.outputTokens(), costUsd(usage)), StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not write " + USAGE_LOG + ": " + e.getMessage());
        }
    }

    private String usageLine(Usage usage) {
        long cached = usage.cacheReadInputTokens().orElse(0L);
        double cost = costUsd(usage);
        return String.format(Locale.ROOT, "%,d input tokens (%,d from cache), %,d output tokens%s",
                usage.inputTokens() + usage.cacheCreationInputTokens().orElse(0L) + cached, cached,
                usage.outputTokens(), cost < 0 ? "" : String.format(Locale.ROOT, ", about $%.3f", cost));
    }

    /** Claude Opus 5.5 list prices per million tokens; -1 for other models (no price table kept here). */
    private double costUsd(Usage usage) {
        if (!model.equals(DEFAULT_MODEL)) {
            return -1;
        }
        return (usage.inputTokens() * 4.0
                + usage.cacheCreationInputTokens().orElse(0L) * 5.0
                + usage.cacheReadInputTokens().orElse(0L) * 0.20
                + usage.outputTokens() * 20.0) / 1_000_000;
    }

    private synchronized AnthropicClient client() {
        if (client == null) {
            client = AnthropicOkHttpClient.fromEnv();
        }
        return client;
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
