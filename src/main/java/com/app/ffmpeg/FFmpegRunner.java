package com.app.ffmpeg;

import com.app.model.AudioChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class FFmpegRunner {
    private static final Logger LOG = LoggerFactory.getLogger(FFmpegRunner.class);

    private final String ffmpegBinary;
    private final String ffprobeBinary;

    public FFmpegRunner() {
        this("ffmpeg", "ffprobe");
    }

    public FFmpegRunner(String ffmpegBinary, String ffprobeBinary) {
        this.ffmpegBinary = ffmpegBinary;
        this.ffprobeBinary = ffprobeBinary;
    }

    public void verifyInstalled() {
        verifyBinary(ffmpegBinary);
        verifyBinary(ffprobeBinary);
    }

    private void verifyBinary(String binary) {
        try {
            Process p = new ProcessBuilder(binary, "-version")
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new FFmpegException(binary + " did not respond to -version within 10s");
            }
            if (p.exitValue() != 0) {
                throw new FFmpegException(binary + " -version exited with code " + p.exitValue());
            }
        } catch (IOException e) {
            throw new FFmpegException(
                    binary + " was not found on PATH. Please install FFmpeg and ensure '" + binary + "' is executable.",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FFmpegException("Interrupted while checking " + binary, e);
        }
    }

    public double probeDurationSeconds(Path video) {
        List<String> cmd = List.of(
                ffprobeBinary,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                video.toAbsolutePath().toString()
        );
        String output = run(cmd, null).trim();
        if (output.isEmpty()) {
            throw new FFmpegException("Could not probe duration for " + video);
        }
        // stderr is merged into stdout (see run()), so ffprobe warnings such as
        // "[mov,mp4,...] wrong sample count" can appear alongside the duration.
        // Scan for the line that actually parses as a number.
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
                // not the duration line (likely a warning); keep looking
            }
        }
        throw new FFmpegException("Unexpected ffprobe duration output: '" + output + "'");
    }

    public Path extractAudio(Path video, Path outputWav, Consumer<String> logSink) {
        List<String> cmd = List.of(
                ffmpegBinary,
                "-y",
                "-i", video.toAbsolutePath().toString(),
                "-vn",
                "-acodec", "pcm_s16le",
                "-ar", "16000",
                "-ac", "1",
                outputWav.toAbsolutePath().toString()
        );
        run(cmd, logSink);
        if (!Files.exists(outputWav)) {
            throw new FFmpegException("Audio extraction did not produce " + outputWav);
        }
        return outputWav;
    }

    public List<AudioChunk> chunkAudio(Path wav,
                                       Path chunkDir,
                                       double totalDurationSeconds,
                                       double chunkSeconds,
                                       double overlapSeconds,
                                       Consumer<String> logSink) {
        if (chunkSeconds <= overlapSeconds) {
            throw new IllegalArgumentException("chunkSeconds must exceed overlapSeconds");
        }
        try {
            Files.createDirectories(chunkDir);
        } catch (IOException e) {
            throw new FFmpegException("Could not create chunk dir " + chunkDir, e);
        }

        List<AudioChunk> chunks = new ArrayList<>();
        double step = chunkSeconds - overlapSeconds;
        int index = 0;
        for (double start = 0.0; start < totalDurationSeconds; start += step) {
            double end = Math.min(start + chunkSeconds, totalDurationSeconds);
            double dur = end - start;
            if (dur <= 0.1) {
                break;
            }
            Path chunkFile = chunkDir.resolve(String.format("chunk_%04d.wav", index));
            List<String> cmd = List.of(
                    ffmpegBinary,
                    "-y",
                    "-ss", formatSeconds(start),
                    "-t", formatSeconds(dur),
                    "-i", wav.toAbsolutePath().toString(),
                    "-acodec", "pcm_s16le",
                    "-ar", "16000",
                    "-ac", "1",
                    chunkFile.toAbsolutePath().toString()
            );
            run(cmd, logSink);
            chunks.add(new AudioChunk(index, chunkFile, start, end));
            index++;
            if (end >= totalDurationSeconds) {
                break;
            }
        }
        return chunks;
    }

    public Path burnSubtitles(Path video, Path srt, Path output, Consumer<String> logSink) {
        String escapedSrt = escapeForSubtitlesFilter(srt.toAbsolutePath().toString());
        List<String> cmd = List.of(
                ffmpegBinary,
                "-y",
                "-i", video.toAbsolutePath().toString(),
                "-vf", "subtitles='" + escapedSrt + "'",
                "-c:a", "copy",
                output.toAbsolutePath().toString()
        );
        run(cmd, logSink);
        if (!Files.exists(output)) {
            throw new FFmpegException("Subtitle burn did not produce " + output);
        }
        return output;
    }

    private static String escapeForSubtitlesFilter(String path) {
        // FFmpeg's subtitles filter on Windows needs forward slashes and the drive colon escaped.
        String forward = path.replace('\\', '/');
        return forward.replace(":", "\\:").replace("'", "\\'");
    }

    private static String formatSeconds(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", seconds);
    }

    private String run(List<String> cmd, Consumer<String> logSink) {
        if (logSink != null) {
            logSink.accept("$ " + String.join(" ", cmd));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new FFmpegException("Failed to start: " + cmd.get(0), e);
        }

        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
                if (logSink != null) {
                    logSink.accept(line);
                } else {
                    LOG.debug("{}", line);
                }
            }
        } catch (IOException e) {
            throw new FFmpegException("Failed reading output of " + cmd.get(0), e);
        }

        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new FFmpegException("Interrupted running " + cmd.get(0), e);
        }
        if (code != 0) {
            throw new FFmpegException(cmd.get(0) + " exited with code " + code + ":\n" + out);
        }
        return out.toString();
    }
}
