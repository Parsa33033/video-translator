package com.app.subtitle;

import com.app.model.Segment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class SrtWriter {

    private SrtWriter() {}

    public static Path write(Path target, List<Segment> segments) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            int n = 1;
            for (Segment seg : segments) {
                if (seg.text().isBlank()) continue;
                w.write(Integer.toString(n++));
                w.write('\n');
                w.write(formatTimestamp(seg.start()));
                w.write(" --> ");
                w.write(formatTimestamp(seg.end()));
                w.write('\n');
                w.write(seg.text());
                w.write("\n\n");
            }
        }
        return target;
    }

    static String formatTimestamp(double seconds) {
        if (seconds < 0) seconds = 0;
        long totalMillis = Math.round(seconds * 1000.0);
        long hours = totalMillis / 3_600_000L;
        long remainder = totalMillis % 3_600_000L;
        long minutes = remainder / 60_000L;
        remainder = remainder % 60_000L;
        long secs = remainder / 1000L;
        long millis = remainder % 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, secs, millis);
    }
}
