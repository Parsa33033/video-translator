package com.app.core.pipeline;

import com.app.model.SourceLanguage;

import java.nio.file.Path;

public record PipelineRequest(
        Path inputVideo,
        Path outputVideo,
        SourceLanguage sourceLanguage,
        String geminiApiKey,
        boolean burnSubtitles,
        boolean writeVtt
) {}
