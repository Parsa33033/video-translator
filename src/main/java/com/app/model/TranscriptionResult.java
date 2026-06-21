package com.app.model;

import java.util.List;

public record TranscriptionResult(List<Segment> segments) {
    public TranscriptionResult {
        segments = List.copyOf(segments);
    }
}
