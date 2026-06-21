package com.app.core.pipeline;

import com.app.alignment.TimelineAligner;
import com.app.ffmpeg.FFmpegRunner;
import com.app.gemini.GeminiClient;
import com.app.model.AudioChunk;
import com.app.model.PipelinePhase;
import com.app.model.Segment;
import com.app.model.TranscriptionResult;
import com.app.subtitle.SrtWriter;
import com.app.subtitle.VttWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class TranscriptionPipeline {
    private static final Logger LOG = LoggerFactory.getLogger(TranscriptionPipeline.class);
    private static final double SHORT_CHUNK_SECONDS = 15.0;
    private static final double LONG_CHUNK_SECONDS = 60.0;
    private static final double LONG_VIDEO_THRESHOLD_SECONDS = 5 * 60.0;
    private static final double OVERLAP_SECONDS = 0.5;
    private static final int MAX_CONCURRENT_API_CALLS = 5;

    private final FFmpegRunner ffmpeg;

    public TranscriptionPipeline() {
        this(new FFmpegRunner());
    }

    public TranscriptionPipeline(FFmpegRunner ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public PipelineResult run(PipelineRequest req, ProgressListener listener) throws IOException {
        validate(req, listener);

        Path workDir = Files.createTempDirectory("video-transcriber-");
        Path wav = workDir.resolve("audio.wav");
        Path chunkDir = workDir.resolve("chunks");
        Path srtFile = sibling(req.outputVideo(), ".srt");
        Path vttFile = req.writeVtt() ? sibling(req.outputVideo(), ".vtt") : null;

        try {
            listener.onPhase(PipelinePhase.EXTRACTING_AUDIO);
            listener.onLog("Extracting audio from " + req.inputVideo().getFileName());
            double duration = ffmpeg.probeDurationSeconds(req.inputVideo());
            listener.onLog("Video duration: " + String.format(java.util.Locale.ROOT, "%.2fs", duration));
            ffmpeg.extractAudio(req.inputVideo(), wav, listener::onLog);
            listener.onProgress(cumulativeWeight(PipelinePhase.EXTRACTING_AUDIO));

            listener.onPhase(PipelinePhase.CHUNKING);
            double chunkSeconds = duration > LONG_VIDEO_THRESHOLD_SECONDS
                    ? LONG_CHUNK_SECONDS
                    : SHORT_CHUNK_SECONDS;
            listener.onLog("Chunking audio (" + chunkSeconds + "s chunks, " + OVERLAP_SECONDS + "s overlap"
                    + (duration > LONG_VIDEO_THRESHOLD_SECONDS ? " — long video, using minute-long chunks)" : ")"));
            List<AudioChunk> chunks = ffmpeg.chunkAudio(wav, chunkDir, duration, chunkSeconds, OVERLAP_SECONDS, listener::onLog);
            listener.onLog("Produced " + chunks.size() + " chunks");
            listener.onProgress(cumulativeWeight(PipelinePhase.CHUNKING));

            listener.onPhase(PipelinePhase.TRANSLATING);
            Map<Integer, TranscriptionResult> results = translateChunks(chunks, req, listener);
            listener.onProgress(cumulativeWeight(PipelinePhase.TRANSLATING));

            listener.onPhase(PipelinePhase.ALIGNING);
            listener.onLog("Aligning timestamps across " + results.size() + " chunk responses");
            List<Segment> aligned = new TimelineAligner().align(chunks, results);
            listener.onLog("Final segment count: " + aligned.size());
            listener.onProgress(cumulativeWeight(PipelinePhase.ALIGNING));

            listener.onPhase(PipelinePhase.WRITING_SUBTITLES);
            SrtWriter.write(srtFile, aligned);
            listener.onLog("Wrote SRT: " + srtFile);
            if (vttFile != null) {
                VttWriter.write(vttFile, aligned);
                listener.onLog("Wrote VTT: " + vttFile);
            }
            listener.onProgress(cumulativeWeight(PipelinePhase.WRITING_SUBTITLES));

            Path renderedVideo = null;
            if (req.burnSubtitles()) {
                listener.onPhase(PipelinePhase.RENDERING);
                listener.onLog("Burning subtitles into video");
                ffmpeg.burnSubtitles(req.inputVideo(), srtFile, req.outputVideo(), listener::onLog);
                renderedVideo = req.outputVideo();
            } else {
                listener.onLog("Skipping video render — subtitle file is the final output.");
            }
            listener.onProgress(1.0);
            listener.onLog("Done.");

            return new PipelineResult(renderedVideo, srtFile, vttFile, aligned);
        } finally {
            cleanup(workDir, listener);
        }
    }

    private void validate(PipelineRequest req, ProgressListener listener) {
        listener.onPhase(PipelinePhase.VALIDATING);
        listener.onLog("Checking FFmpeg installation");
        ffmpeg.verifyInstalled();
        if (!Files.isRegularFile(req.inputVideo())) {
            throw new IllegalArgumentException("Input video does not exist: " + req.inputVideo());
        }
        if (req.geminiApiKey() == null || req.geminiApiKey().isBlank()) {
            throw new IllegalArgumentException("Gemini API key is required");
        }
        if (req.outputVideo() == null) {
            throw new IllegalArgumentException("Output video path is required");
        }
        Path outDir = req.outputVideo().getParent();
        if (outDir != null) {
            try {
                Files.createDirectories(outDir);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not create output directory: " + outDir, e);
            }
        }
        listener.onProgress(cumulativeWeight(PipelinePhase.VALIDATING));
    }

    private Map<Integer, TranscriptionResult> translateChunks(List<AudioChunk> chunks,
                                                              PipelineRequest req,
                                                              ProgressListener listener) {
        GeminiClient client = new GeminiClient(req.geminiApiKey());
        ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENT_API_CALLS, r -> {
            Thread t = new Thread(r, "gemini-worker");
            t.setDaemon(true);
            return t;
        });
        Semaphore inflight = new Semaphore(MAX_CONCURRENT_API_CALLS);
        AtomicInteger done = new AtomicInteger();
        int total = chunks.size();
        double phaseBase = cumulativeWeight(PipelinePhase.CHUNKING);
        double phaseSpan = PipelinePhase.TRANSLATING.weight();

        AtomicInteger skipped = new AtomicInteger();
        try {
            List<CompletableFuture<Map.Entry<Integer, TranscriptionResult>>> futures = new ArrayList<>();
            for (AudioChunk chunk : chunks) {
                CompletableFuture<Map.Entry<Integer, TranscriptionResult>> f = CompletableFuture.supplyAsync(() -> {
                    try {
                        inflight.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted waiting for API slot", e);
                    }
                    try {
                        listener.onLog("Translating chunk " + chunk.index()
                                + " (" + String.format(java.util.Locale.ROOT, "%.1f", chunk.startSeconds())
                                + "s → " + String.format(java.util.Locale.ROOT, "%.1f", chunk.endSeconds()) + "s)");
                        TranscriptionResult tr;
                        try {
                            tr = client.transcribeChunk(chunk, req.sourceLanguage(), listener::onLog);
                            listener.onLog("Chunk " + chunk.index() + " done (" + tr.segments().size() + " segments)");
                        } catch (RuntimeException ex) {
                            tr = new TranscriptionResult(List.of());
                            skipped.incrementAndGet();
                            String reason = ex.getMessage();
                            if (reason != null) {
                                reason = reason.lines().findFirst().orElse(reason);
                                if (reason.length() > 200) reason = reason.substring(0, 200) + "...";
                            }
                            listener.onLog("Chunk " + chunk.index() + " FAILED after retries — skipping. Reason: " + reason);
                            LOG.warn("Skipping chunk {} after retries", chunk.index(), ex);
                        }
                        int finished = done.incrementAndGet();
                        double frac = phaseBase + phaseSpan * ((double) finished / total);
                        listener.onProgress(frac);
                        return Map.entry(chunk.index(), tr);
                    } finally {
                        inflight.release();
                    }
                }, pool);
                futures.add(f);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            Map<Integer, TranscriptionResult> map = new LinkedHashMap<>();
            futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparingInt(Map.Entry::getKey))
                    .forEach(e -> map.put(e.getKey(), e.getValue()));
            int skips = skipped.get();
            if (skips > 0) {
                listener.onLog("WARNING: " + skips + " of " + total
                        + " chunks were skipped — those sections of the video will have no subtitles.");
            }
            return map;
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
    }

    private static double cumulativeWeight(PipelinePhase upTo) {
        double sum = 0.0;
        for (PipelinePhase p : PipelinePhase.values()) {
            sum += p.weight();
            if (p == upTo) break;
        }
        return Math.min(1.0, sum);
    }

    private static Path sibling(Path video, String suffix) {
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return video.resolveSibling(base + suffix);
    }

    private static void cleanup(Path workDir, ProgressListener listener) {
        if (workDir == null || !Files.exists(workDir)) return;
        try (Stream<Path> walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to clean work dir {}: {}", workDir, e.getMessage());
            listener.onLog("Warning: could not clean " + workDir + ": " + e.getMessage());
        }
    }
}
