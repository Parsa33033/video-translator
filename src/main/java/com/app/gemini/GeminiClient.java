package com.app.gemini;

import com.app.model.AudioChunk;
import com.app.model.Segment;
import com.app.model.SourceLanguage;
import com.app.model.TranscriptionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GeminiClient {
    private static final Logger LOG = LoggerFactory.getLogger(GeminiClient.class);
    private static final String DEFAULT_MODEL = "gemini-3.1-flash-lite";
    private static final String ENDPOINT_FMT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final int MAX_ATTEMPTS = 5;
    private static final long[] BACKOFF_MS = {1500L, 3000L, 6000L, 12000L, 20000L};
    private static final long MAX_RETRY_AFTER_MS = 90_000L;
    private static final Pattern RETRY_AFTER_PATTERN =
            Pattern.compile("retry in ([0-9.]+)\\s*s", Pattern.CASE_INSENSITIVE);

    private final String apiKey;
    private final String model;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public GeminiClient(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Gemini API key is required");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public TranscriptionResult transcribeChunk(AudioChunk chunk,
                                               SourceLanguage source,
                                               Consumer<String> logSink) {
        byte[] audio;
        try {
            audio = Files.readAllBytes(chunk.file());
        } catch (IOException e) {
            throw new GeminiException("Failed to read chunk file " + chunk.file(), e);
        }
        String base64 = Base64.getEncoder().encodeToString(audio);
        String body = buildRequestBody(base64, source, chunk.durationSeconds());

        RuntimeException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                String json = sendRequest(body);
                return parseResponse(json, chunk);
            } catch (RuntimeException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS - 1) {
                    long delay = computeBackoffMs(attempt, e);
                    String brief = briefMessage(e.getMessage());
                    if (logSink != null) {
                        logSink.accept("Gemini chunk " + chunk.index()
                                + " attempt " + (attempt + 1) + " failed: " + brief
                                + " — retrying in " + delay + "ms");
                    }
                    LOG.warn("Gemini attempt {} failed for chunk {}: {}", attempt + 1, chunk.index(), brief);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new GeminiException("Interrupted between Gemini retries", ie);
                    }
                }
            }
        }
        throw new GeminiException("Gemini transcription failed for chunk " + chunk.index()
                + " after " + MAX_ATTEMPTS + " attempts: " + briefMessage(last == null ? "" : last.getMessage()),
                last);
    }

    private static long computeBackoffMs(int attempt, RuntimeException e) {
        String msg = e.getMessage();
        if (msg != null) {
            Matcher m = RETRY_AFTER_PATTERN.matcher(msg);
            if (m.find()) {
                try {
                    double secs = Double.parseDouble(m.group(1));
                    long ms = Math.round(secs * 1000.0) + 1000L; // small buffer
                    return Math.min(Math.max(ms, 1000L), MAX_RETRY_AFTER_MS);
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
    }

    private static String briefMessage(String msg) {
        if (msg == null) return "";
        String first = msg.lines().findFirst().orElse(msg);
        return first.length() > 160 ? first.substring(0, 160) + "..." : first;
    }

    private String buildRequestBody(String base64Audio, SourceLanguage source, double durationSeconds) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode message = contents.addObject();
        ArrayNode parts = message.putArray("parts");

        ObjectNode textPart = parts.addObject();
        textPart.put("text", buildPrompt(source, durationSeconds));

        ObjectNode audioPart = parts.addObject();
        ObjectNode inlineData = audioPart.putObject("inline_data");
        inlineData.put("mime_type", "audio/wav");
        inlineData.put("data", base64Audio);

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("response_mime_type", "application/json");
        generationConfig.put("temperature", 0.0);
        // Bound the response so a repetition loop ("ah, ah, ah, ...") cannot blow past the token budget
        // and truncate the JSON. 4096 tokens is generous for ~15 s of speech.
        generationConfig.put("maxOutputTokens", 4096);

        try {
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new GeminiException("Failed to serialise Gemini request", e);
        }
    }

    private String buildPrompt(SourceLanguage source, double durationSeconds) {
        return "You are a professional translator, transcriber, and subtitle timer. "
                + "The attached audio is in " + source.displayName() + " and is approximately "
                + String.format(java.util.Locale.ROOT, "%.2f", durationSeconds) + " seconds long. "
                + "Transcribe what is spoken, then translate it into grammatical, natural English that "
                + "stays as close to the source as English syntax allows. "
                + "Return ONLY valid JSON with this exact shape:\n"
                + "{\n  \"segments\": [\n    { \"start\": <seconds from start of clip>,"
                + " \"end\": <seconds from start of clip>, \"text\": \"<English translation>\" }\n  ]\n}\n"
                + "\n"
                + "TIMING RULES — these are CRITICAL because the output drives subtitles that must match the audio frame-by-frame:\n"
                + "- \"start\" = the EXACT moment the FIRST WORD of the segment begins to be spoken in the audio. "
                + "Not the moment before, not the moment after — the first phoneme of the first word.\n"
                + "- \"end\" = the EXACT moment the LAST WORD of the segment finishes being spoken. "
                + "The last phoneme of the last word, not a rounded boundary.\n"
                + "- Use sub-second precision: e.g. 3.42, not 3.5 or 3.0. Rounding to whole seconds is WRONG.\n"
                + "- Timestamps are relative to the START of THIS clip (0.0 = first audio sample of this clip).\n"
                + "- Do NOT pad segments. The subtitle should appear precisely when speech starts and "
                + "disappear precisely when it ends — viewers will hear the audio and see the text in sync.\n"
                + "- If there is a silent gap longer than ~0.5 s between two utterances, SPLIT them into "
                + "two separate segments; the gap becomes a no-subtitle interval. Do not stretch one "
                + "segment across the silence.\n"
                + "- Segments MUST be in chronological order and MUST NOT overlap each other.\n"
                + "- Do NOT guess, round, or invent timestamps. If you genuinely cannot pinpoint when a "
                + "phrase begins or ends, OMIT that segment rather than emit a wrong timestamp.\n"
                + "- One segment = one sentence or one natural breath-group. Split long sentences at "
                + "natural pauses so each subtitle is readable in ~1–6 seconds.\n"
                + "\n"
                + "TRANSLATION RULES:\n"
                + "- Produce English that an English speaker would actually say. Reorder words and adjust "
                + "grammar as needed so the result reads naturally — for example, French \"te faire\" becomes "
                + "\"make you\", not \"you make\". A word-by-word rendering that violates English grammar is WRONG.\n"
                + "- Stay faithful to the source: do NOT paraphrase, summarise, expand, soften, or invent content. "
                + "Translate what is actually said, with the same meaning, register, and tone — no more, no less.\n"
                + "- Do NOT censor, bowdlerise, or substitute milder words. Profanity, slurs, sexual language, "
                + "slang, and other adult or explicit terms must be rendered with their direct, equivalent English "
                + "words written out in full — never asterisks, [redacted], or euphemisms. This is a faithful "
                + "translation task, not a sanitised one.\n"
                + "- Match the speaker's register: vulgar source → vulgar English, formal source → formal English.\n"
                + "- Preserve filler words, hesitations, and brief repetitions if they are clearly audible.\n"
                + "- For extended repetitive vocalisations (laughter, moans, screams, sustained 'oh', 'ah', 'mm', "
                + "crying, sighing, etc.) write ONE summarising segment such as \"[laughter]\", \"[moaning]\", "
                + "or \"Oh, oh, oh!\" — do NOT transcribe each repetition. Never repeat the same word or "
                + "interjection more than 4 times in a row. This is a hard rule to prevent output loops.\n"
                + "- If a region has no speech at all, omit it entirely.\n"
                + "- Do not include any text outside the JSON object.";
    }

    private String sendRequest(String body) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(String.format(ENDPOINT_FMT, model)))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GeminiException("Network error talking to Gemini: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiException("Interrupted talking to Gemini", e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new GeminiException("Gemini HTTP " + resp.statusCode() + ": "
                    + truncate(resp.body(), 500));
        }
        return resp.body();
    }

    private TranscriptionResult parseResponse(String json, AudioChunk chunk) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (IOException e) {
            throw new GeminiException("Gemini response was not valid JSON", e);
        }
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new GeminiException("Gemini response had no candidates: " + truncate(json, 300));
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new GeminiException("Gemini response had no content parts");
        }
        StringBuilder textBuilder = new StringBuilder();
        for (JsonNode part : parts) {
            JsonNode t = part.get("text");
            if (t != null && t.isTextual()) {
                textBuilder.append(t.asText());
            }
        }
        String text = textBuilder.toString().trim();
        if (text.isEmpty()) {
            return new TranscriptionResult(List.of());
        }
        text = stripMarkdownFence(text);

        double clipDuration = chunk.durationSeconds();

        JsonNode payload = tryParseSegmentsPayload(text);
        boolean repaired = false;
        if (payload == null) {
            // Truncated JSON (token-cap hit on runaway output): trim to the last complete
            // segment object and re-parse. Iterative scan — no recursion, no regex.
            String trimmed = trimToLastCompleteSegment(text);
            if (trimmed != null) {
                payload = tryParseSegmentsPayload(trimmed);
                repaired = payload != null;
            }
        }
        if (payload == null) {
            throw new GeminiException("Gemini did not return JSON segments. Got: " + truncate(text, 300));
        }

        List<Segment> out = new ArrayList<>();
        for (JsonNode s : payload.path("segments")) {
            double start = s.path("start").asDouble(Double.NaN);
            double end = s.path("end").asDouble(Double.NaN);
            String segText = s.path("text").asText("").trim();
            if (Double.isNaN(start) || Double.isNaN(end) || segText.isEmpty()) continue;
            start = Math.max(0.0, Math.min(start, clipDuration));
            end = Math.max(start, Math.min(end, clipDuration));
            out.add(new Segment(start, end, segText));
        }
        if (repaired) {
            LOG.warn("Recovered {} segments from truncated Gemini response for chunk {}",
                    out.size(), chunk.index());
        }
        return new TranscriptionResult(out);
    }

    private JsonNode tryParseSegmentsPayload(String text) {
        try {
            JsonNode node = mapper.readTree(text);
            return node.path("segments").isArray() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Walks the raw model output once, tracking string state and brace depth, and returns
     * a substring trimmed at the last fully-closed segment object plus "]}". Returns null
     * if no complete segment object was emitted before truncation.
     */
    private static String trimToLastCompleteSegment(String raw) {
        int braces = 0;
        boolean inString = false;
        boolean escape = false;
        int lastSegmentEnd = -1;
        int n = raw.length();
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                braces++;
            } else if (c == '}') {
                braces--;
                // depth 1 = we just closed a segment object inside the outer { "segments": [ ... ] }
                if (braces == 1) {
                    lastSegmentEnd = i;
                }
            }
        }
        if (lastSegmentEnd < 0) return null;
        return raw.substring(0, lastSegmentEnd + 1) + "]}";
    }

    private static String stripMarkdownFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) {
                t = t.substring(firstNl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
            t = t.trim();
        }
        return t;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
