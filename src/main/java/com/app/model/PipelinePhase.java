package com.app.model;

public enum PipelinePhase {
    VALIDATING("Validating", 0.05),
    EXTRACTING_AUDIO("Extracting audio", 0.10),
    CHUNKING("Chunking audio", 0.10),
    TRANSLATING("Translating", 0.55),
    ALIGNING("Aligning timestamps", 0.05),
    WRITING_SUBTITLES("Writing subtitles", 0.05),
    RENDERING("Rendering video", 0.10);

    private final String label;
    private final double weight;

    PipelinePhase(String label, double weight) {
        this.label = label;
        this.weight = weight;
    }

    public String label() { return label; }
    public double weight() { return weight; }
}
