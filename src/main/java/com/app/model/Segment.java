package com.app.model;

public record Segment(double start, double end, String text) {
    public Segment {
        if (end < start) {
            end = start;
        }
        if (text == null) {
            text = "";
        }
        text = text.trim();
    }

    public Segment shifted(double offset) {
        return new Segment(start + offset, end + offset, text);
    }
}
