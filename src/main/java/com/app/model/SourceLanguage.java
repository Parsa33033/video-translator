package com.app.model;

public enum SourceLanguage {
    FRENCH("French", "fr"),
    GERMAN("German", "de"),
    ITALIAN("Italian", "it"),
    DUTCH("Dutch", "nl");

    private final String displayName;
    private final String code;

    SourceLanguage(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    public String displayName() { return displayName; }
    public String code() { return code; }

    @Override
    public String toString() { return displayName; }
}
