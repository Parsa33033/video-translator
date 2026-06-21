# How to Run

Java desktop app that extracts audio from a video, translates speech to English via the Gemini API, generates time-aligned subtitles, and burns them back into the video.

## 1. Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| **JDK** | 21 (LTS) | `java -version` must report 21.x |
| **Maven** | 3.9+ | `mvn -version` |
| **FFmpeg** | any recent build | Both `ffmpeg` **and** `ffprobe` must be on `PATH` |
| **Gemini API key** | — | Get one from [Google AI Studio](https://aistudio.google.com/app/apikey) |

### Verify FFmpeg

```powershell
ffmpeg -version
ffprobe -version
```

If either command is not found, install FFmpeg:

- **Windows (Chocolatey):** `choco install ffmpeg`
- **Windows (Scoop):** `scoop install ffmpeg`
- **macOS (Homebrew):** `brew install ffmpeg`
- **Linux (apt):** `sudo apt install ffmpeg`

Then close and reopen your terminal so the new `PATH` is picked up.

## 2. Build

From the project root (`C:\Users\Parsa\IdeaProjects\video-transcriber`):

```powershell
mvn clean compile
```

This downloads JavaFX 21, Jackson, and SLF4J and compiles all sources to `target/classes`.

## 3. Run

```powershell
mvn javafx:run
```

The first launch can take a few seconds while the JavaFX runtime starts. A window titled **"Video Transcriber — Translate & Subtitle"** should appear.

> If FFmpeg is missing, the app pops up a blocking error dialog and disables the **Transcribe** button. Install FFmpeg, restart the app.

## 4. Using the App

1. **Video file** — click *Browse...* and pick a `.mp4`, `.mov`, or `.mkv`.
2. **Source language** — choose French, German, Italian, or Dutch. Output is always English.
3. **Gemini API key** — paste your key. It is remembered between runs (via Java Preferences, stored per-user).
4. **Output path** — defaults to `<input>_en.mp4` (or `.srt`, depending on the next setting). Change with *Browse...* if you like.
5. **Burn subtitles into video** — checkbox; default ON, remembered between runs.
   - ON → the pipeline re-encodes the video with hard-burned subtitles and also writes a sidecar `.srt`.
   - OFF → only the `.srt` is produced. Much faster (no video re-encode) and the original video is untouched. The output field switches to *Output SRT file* automatically.
6. Click **Transcribe & Translate**.

While running you will see:

- The current phase (e.g. *Extracting audio*, *Translating*, *Rendering video*)
- A unified progress bar across all phases
- A live log console showing the FFmpeg commands and per-chunk Gemini activity

When the pipeline finishes:

- The output `.mp4` is written next to the path you chose
- An `.srt` file is written next to the output video
- A preview opens in the embedded player — click **Play preview**
- Click **Open output folder** to reveal the files in your file manager

## 5. What the Pipeline Does

1. **Validate** — checks FFmpeg, input exists, output dir writable, API key non-empty
2. **Extract audio** — `ffmpeg -vn -acodec pcm_s16le -ar 16000 -ac 1` (16 kHz mono WAV)
3. **Chunk** — 500 ms overlap; **15-second windows** for videos ≤ 5 min, **60-second windows** for longer videos (fewer API calls, less likely to hit rate limits)
4. **Translate** — up to 5 concurrent Gemini calls; each chunk retried up to 3× with 1 s/2 s/4 s backoff
5. **Align** — shifts chunk-relative timestamps to absolute, drops duplicates from the overlap window, enforces monotonic ordering
6. **Write subtitles** — `.srt` (always); `.vtt` can be enabled in `PipelineRequest`
7. **Render** — burns subtitles into the video with `ffmpeg -vf subtitles=...`
8. **Cleanup** — all intermediate WAV files and chunk dir are deleted

## 6. Troubleshooting

| Symptom | Fix |
|---------|-----|
| *"FFmpeg missing"* dialog at startup | Install FFmpeg and reopen the terminal before running `mvn javafx:run` |
| `Gemini HTTP 400/403` in the log | Bad/expired API key, or the model id is not enabled for your project — see *Changing the Model* below |
| `Gemini HTTP 429` | Rate-limited. Retries handle transient bursts, but for very long videos consider lowering concurrency in `TranscriptionPipeline.MAX_CONCURRENT_API_CALLS` |
| Preview shows audio but no video | JavaFX's `MediaView` only decodes a limited set of codecs (H.264/AAC in MP4). The output file is still valid — open it in VLC instead |
| `Could not open folder` | The platform doesn't expose `java.awt.Desktop`. Open the path shown in the log manually |

## 7. Changing the Model

The Gemini model id is set in `com.app.gemini.GeminiClient`:

```java
private static final String DEFAULT_MODEL = "gemini-3.1-flash-lite";
```

If your account uses a different id (for example `gemini-2.5-flash`), edit that constant and rebuild.

## 8. Tuning Knobs

| Constant | Location | Default | What it controls |
|----------|----------|---------|------------------|
| `SHORT_CHUNK_SECONDS` | `TranscriptionPipeline` | `15.0` | Chunk length used when the video is ≤ `LONG_VIDEO_THRESHOLD_SECONDS` |
| `LONG_CHUNK_SECONDS` | `TranscriptionPipeline` | `60.0` | Chunk length used when the video is longer than the threshold |
| `LONG_VIDEO_THRESHOLD_SECONDS` | `TranscriptionPipeline` | `300.0` | Duration above which the longer chunk size kicks in |
| `OVERLAP_SECONDS` | `TranscriptionPipeline` | `0.5` | Overlap window between consecutive chunks |
| `MAX_CONCURRENT_API_CALLS` | `TranscriptionPipeline` | `5` | Hard cap on in-flight Gemini requests |
| `MAX_ATTEMPTS` / `BACKOFF_MS` | `GeminiClient` | `5` / `1.5s,3s,6s,12s,20s` | Retry policy per chunk (429s honour Gemini's `retry in Xs` hint instead) |

## 9. Packaging (Optional)

To produce a runnable distribution that does not require Maven on the user's machine, use `jlink` via the JavaFX plugin:

```powershell
mvn clean javafx:jlink
```

The resulting image lives in `target/app/`. Run it with `target/app/bin/app`.

> The end-user still needs **FFmpeg** installed separately — it is not bundled.
