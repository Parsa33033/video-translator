package com.app.ffmpeg;

public class FFmpegException extends RuntimeException {
    public FFmpegException(String message) {
        super(message);
    }

    public FFmpegException(String message, Throwable cause) {
        super(message, cause);
    }
}
