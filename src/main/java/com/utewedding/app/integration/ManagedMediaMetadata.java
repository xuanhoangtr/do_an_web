package com.utewedding.app.integration;

public record ManagedMediaMetadata(String mimeType, String format, long bytes,
                                   int width, int height, long pixelCount) {}