package com.app.core.pipeline;

import com.app.model.PipelinePhase;

public interface ProgressListener {
    void onPhase(PipelinePhase phase);
    void onProgress(double overallFraction);
    void onLog(String line);

    static ProgressListener noop() {
        return new ProgressListener() {
            @Override public void onPhase(PipelinePhase phase) {}
            @Override public void onProgress(double overallFraction) {}
            @Override public void onLog(String line) {}
        };
    }
}
