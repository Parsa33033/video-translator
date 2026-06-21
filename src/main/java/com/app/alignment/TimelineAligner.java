package com.app.alignment;

import com.app.model.AudioChunk;
import com.app.model.Segment;
import com.app.model.TranscriptionResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class TimelineAligner {

    public List<Segment> align(List<AudioChunk> chunks, Map<Integer, TranscriptionResult> chunkResults) {
        List<Segment> absolute = new ArrayList<>();
        for (AudioChunk chunk : chunks) {
            TranscriptionResult result = chunkResults.get(chunk.index());
            if (result == null) {
                continue;
            }
            for (Segment seg : result.segments()) {
                absolute.add(seg.shifted(chunk.startSeconds()));
            }
        }
        absolute.sort(Comparator.comparingDouble(Segment::start)
                .thenComparingDouble(Segment::end));
        return repair(absolute);
    }

    private List<Segment> repair(List<Segment> sorted) {
        List<Segment> out = new ArrayList<>();
        for (Segment seg : sorted) {
            if (seg.text().isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                Segment prev = out.get(out.size() - 1);

                // Drop duplicates introduced by chunk overlap windows. Threshold bumped from 1.5s
                // to 2.5s — with 60s chunks the same sentence can appear with timestamps that
                // differ by more than 1.5s between adjacent chunks.
                if (textsMatch(prev.text(), seg.text())
                        && Math.abs(seg.start() - prev.start()) < 2.5) {
                    if (seg.end() > prev.end()) {
                        out.set(out.size() - 1, new Segment(prev.start(), seg.end(), prev.text()));
                    }
                    continue;
                }

                // When two segments overlap, trust the NEW segment's start (it's anchored to when
                // its speech actually begins) and trim the previous segment's end down to match.
                // Pushing this segment's start later — the old behaviour — would delay the subtitle
                // past the moment the speech is heard.
                if (seg.start() < prev.end()) {
                    if (seg.start() > prev.start() + 0.05) {
                        out.set(out.size() - 1, new Segment(prev.start(), seg.start(), prev.text()));
                    } else {
                        // prev would have <50ms duration after trimming — drop it entirely.
                        out.remove(out.size() - 1);
                    }
                }
            }
            out.add(seg);
        }
        return out;
    }

    private static boolean textsMatch(String a, String b) {
        return normalise(a).equals(normalise(b));
    }

    private static String normalise(String s) {
        return s.toLowerCase().replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }
}
