package com.utewedding.app.integration;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Content-based image validation with dimension checks before upload/decode. */
@Component
public class ManagedMediaValidator {
    private static final int BUFFER_SIZE = 8192;
    private final long maxBytes;
    private final int maxWidth;
    private final int maxHeight;
    private final long maxPixels;
    private final Semaphore decodeSlots;

    public ManagedMediaValidator(
            @Value("${media.max-image-size:10485760}") long maxBytes,
            @Value("${media.max-image-width:6000}") int maxWidth,
            @Value("${media.max-image-height:6000}") int maxHeight,
            @Value("${media.max-image-pixels:25000000}") long maxPixels,
            @Value("${media.max-concurrent-decodes:2}") int maxConcurrentDecodes) {
        if (maxBytes <= 0 || maxWidth <= 0 || maxHeight <= 0 || maxPixels <= 0 || maxConcurrentDecodes <= 0) {
            throw new IllegalArgumentException("Media limits must be positive");
        }
        this.maxBytes = maxBytes; this.maxWidth = maxWidth; this.maxHeight = maxHeight;
        this.maxPixels = maxPixels; this.decodeSlots = new Semaphore(maxConcurrentDecodes);
    }

    public ManagedMediaMetadata validate(MultipartFile file) {
        return validate(file, false);
    }

    public ManagedMediaMetadata validateWeddingImage(MultipartFile file) {
        return validate(file, true);
    }

    public ManagedMediaMetadata validateProductImage(MultipartFile file) {
        return validate(file, true);
    }

    private ManagedMediaMetadata validate(MultipartFile file, boolean allowWebp) {
        if (file == null || file.isEmpty()) throw bad(HttpStatus.BAD_REQUEST, "Vui lòng chọn tệp ảnh.");
        if (file.getSize() > maxBytes) throw bad(HttpStatus.PAYLOAD_TOO_LARGE, "Ảnh vượt quá giới hạn byte.");
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = readBounded(input);
            String format = signature(bytes, allowWebp);
            if (format == null) {
                throw bad(HttpStatus.UNSUPPORTED_MEDIA_TYPE, allowWebp
                        ? "Chỉ chấp nhận ảnh JPEG, PNG hoặc WebP hợp lệ."
                        : "Chỉ chấp nhận ảnh JPEG hoặc PNG hợp lệ.");
            }
            if ("webp".equals(format)) {
                int[] dimensions = webpDimensions(bytes);
                if (dimensions == null) {
                    throw bad(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Ảnh WebP hỏng hoặc không đầy đủ.");
                }
                return validatedMetadata(format, bytes.length, dimensions[0], dimensions[1]);
            }
            try (ImageInputStream imageInput = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
                if (imageInput == null) throw bad(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Không đọc được ảnh.");
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw bad(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Định dạng ảnh không được hỗ trợ.");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(imageInput, true, true);
                    if (reader.getNumImages(false) > 1) {
                        throw bad(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Không chấp nhận ảnh nhiều khung hình.");
                    }
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    long pixels = validateDimensions(width, height);
                    if (!decodeSlots.tryAcquire()) throw bad(HttpStatus.SERVICE_UNAVAILABLE, "Hệ thống đang bận xử lý ảnh.");
                    try {
                        BufferedImage decoded = reader.read(0);
                        if (decoded == null) throw bad(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Ảnh hỏng hoặc không đầy đủ.");
                    } finally { decodeSlots.release(); }
                    return new ManagedMediaMetadata("image/" + format, format, bytes.length, width, height, pixels);
                } finally { reader.dispose(); }
            }
        } catch (ResponseStatusException exception) { throw exception;
        } catch (IOException | RuntimeException exception) {
            throw bad(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Ảnh hỏng hoặc không đầy đủ.");
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxBytes, 65536));
        byte[] buffer = new byte[BUFFER_SIZE]; long total = 0; int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw bad(HttpStatus.PAYLOAD_TOO_LARGE, "Ảnh vượt quá giới hạn byte.");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String signature(byte[] bytes, boolean allowWebp) {
        if (bytes.length >= 8 && (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78
                && bytes[3] == 71 && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) return "png";
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "jpeg";
        if (allowWebp && bytes.length >= 12 && fourCc(bytes, 0, "RIFF") && fourCc(bytes, 8, "WEBP")) return "webp";
        return null;
    }

    private ManagedMediaMetadata validatedMetadata(String format, int byteSize, int width, int height) {
        long pixels = validateDimensions(width, height);
        return new ManagedMediaMetadata("image/" + format, format, byteSize, width, height, pixels);
    }

    private long validateDimensions(int width, int height) {
        long pixels = Math.multiplyExact((long) width, height);
        if (width <= 0 || height <= 0 || width > maxWidth || height > maxHeight || pixels > maxPixels) {
            throw bad(HttpStatus.PAYLOAD_TOO_LARGE, "Kích thước hoặc số pixel ảnh vượt giới hạn.");
        }
        return pixels;
    }

    private static int[] webpDimensions(byte[] bytes) {
        if (bytes.length < 20 || !fourCc(bytes, 0, "RIFF") || !fourCc(bytes, 8, "WEBP")
                || readUInt32LE(bytes, 4) + 8 != bytes.length) return null;

        int offset = 12;
        int canvasWidth = -1;
        int canvasHeight = -1;
        int frameWidth = -1;
        int frameHeight = -1;
        int frameCount = 0;
        boolean extended = false;
        while (offset < bytes.length) {
            if (bytes.length - offset < 8) return null;
            String chunk = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            long chunkLength = readUInt32LE(bytes, offset + 4);
            long dataStart = offset + 8L;
            long dataEnd = dataStart + chunkLength;
            long nextChunk = dataEnd + (chunkLength & 1);
            if (dataEnd > bytes.length || nextChunk > bytes.length) return null;
            int dataOffset = (int) dataStart;
            int dataSize = (int) chunkLength;

            if ("VP8X".equals(chunk)) {
                if (offset != 12 || extended || dataSize != 10) return null;
                int flags = bytes[dataOffset] & 255;
                if ((flags & 0xc0) != 0 || (flags & 0x02) != 0
                        || bytes[dataOffset + 1] != 0 || bytes[dataOffset + 2] != 0
                        || bytes[dataOffset + 3] != 0) return null;
                canvasWidth = readUInt24LE(bytes, dataOffset + 4) + 1;
                canvasHeight = readUInt24LE(bytes, dataOffset + 7) + 1;
                extended = true;
            } else if ("ANIM".equals(chunk) || "ANMF".equals(chunk)) {
                return null;
            } else if ("VP8 ".equals(chunk)) {
                int[] dimensions = lossyWebpDimensions(bytes, dataOffset, dataSize);
                if (dimensions == null || ++frameCount > 1) return null;
                frameWidth = dimensions[0];
                frameHeight = dimensions[1];
            } else if ("VP8L".equals(chunk)) {
                int[] dimensions = losslessWebpDimensions(bytes, dataOffset, dataSize);
                if (dimensions == null || ++frameCount > 1) return null;
                frameWidth = dimensions[0];
                frameHeight = dimensions[1];
            }
            offset = (int) nextChunk;
        }

        if (offset != bytes.length || frameCount != 1) return null;
        if (extended) {
            if (canvasWidth != frameWidth || canvasHeight != frameHeight) return null;
            return new int[] {canvasWidth, canvasHeight};
        }
        return new int[] {frameWidth, frameHeight};
    }

    private static int[] lossyWebpDimensions(byte[] bytes, int offset, int length) {
        if (length < 13 || (bytes[offset] & 1) != 0
                || (bytes[offset + 3] & 255) != 0x9d
                || (bytes[offset + 4] & 255) != 0x01
                || (bytes[offset + 5] & 255) != 0x2a) return null;
        int width = (bytes[offset + 6] & 0xff) | ((bytes[offset + 7] & 0x3f) << 8);
        int height = (bytes[offset + 8] & 0xff) | ((bytes[offset + 9] & 0x3f) << 8);
        return width > 0 && height > 0 ? new int[] {width, height} : null;
    }

    private static int[] losslessWebpDimensions(byte[] bytes, int offset, int length) {
        if (length < 5 || (bytes[offset] & 255) != 0x2f || (bytes[offset + 4] & 0xe0) != 0) return null;
        int byte1 = bytes[offset + 1] & 255;
        int byte2 = bytes[offset + 2] & 255;
        int byte3 = bytes[offset + 3] & 255;
        int byte4 = bytes[offset + 4] & 255;
        int width = 1 + (byte1 & 0x3f) + ((byte2 & 0x3f) << 8);
        int height = 1 + (byte2 >>> 6) + (byte3 << 2) + ((byte4 & 0x0f) << 10);
        return new int[] {width, height};
    }

    private static int readUInt24LE(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8) | ((bytes[offset + 2] & 255) << 16);
    }

    private static long readUInt32LE(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
                | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
    }

    private static boolean fourCc(byte[] bytes, int offset, String value) {
        if (offset + value.length() > bytes.length) return false;
        for (int index = 0; index < value.length(); index++) {
            if ((byte) value.charAt(index) != bytes[offset + index]) return false;
        }
        return true;
    }

    private static ResponseStatusException bad(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
