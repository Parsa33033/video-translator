package com.app.core.pipeline;

import com.app.model.Segment;

import java.nio.file.Path;
import java.util.List;

public record PipelineResult(
        Path outputVideo,
        Path srtFile,
        Path vttFile,
        List<Segment> segments
) {}
