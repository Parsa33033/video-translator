package com.app.model;

import java.nio.file.Path;

public record AudioChunk(int index, Path file, double startSeconds, double endSeconds) {
    public double durationSeconds() {
        return endSeconds - startSeconds;
    }
}
