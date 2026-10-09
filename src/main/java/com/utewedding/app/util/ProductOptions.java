package com.utewedding.app.util;

import com.utewedding.app.exception.BusinessConflictException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses simple photo-size choices from a product's existing customization guide. */
public final class ProductOptions {

    private static final Pattern PHOTO_SIZE_IN_GUIDE = Pattern.compile(
            "(?<!\\d)(\\d{1,3})\\s*[x×]\\s*(\\d{1,3})(?!\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHOTO_SIZE_VALUE = Pattern.compile(
            "^\\s*(\\d{1,3})\\s*[x×]\\s*(\\d{1,3})(?:\\s*cm)?\\s*$", Pattern.CASE_INSENSITIVE);

    private ProductOptions() { }

    public static List<String> photoSizes(String guide) {
        if (guide == null || guide.isBlank()) return List.of();
        Matcher matcher = PHOTO_SIZE_IN_GUIDE.matcher(guide);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        while (matcher.find()) {
            int width = Integer.parseInt(matcher.group(1));
            int height = Integer.parseInt(matcher.group(2));
            if (width > 0 && height > 0) values.add(format(width, height));
        }
        return values.size() < 2 ? List.of() : List.copyOf(values);
    }

    public static String resolvePhotoSize(String guide, String submittedValue) {
        List<String> available = photoSizes(guide);
        if (available.isEmpty()) {
            if (submittedValue != null && !submittedValue.isBlank()) {
                throw new BusinessConflictException("Lựa chọn sản phẩm không hợp lệ.");
            }
            return null;
        }
        if (submittedValue == null || submittedValue.isBlank()) {
            throw new BusinessConflictException("Vui lòng chọn kích thước ảnh.");
        }

        Matcher matcher = PHOTO_SIZE_VALUE.matcher(submittedValue);
        if (!matcher.matches()) throw new BusinessConflictException("Kích thước ảnh đã chọn không hợp lệ.");
        String selected = format(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        if (!available.contains(selected)) {
            throw new BusinessConflictException("Kích thước ảnh đã chọn không còn khả dụng. Vui lòng tải lại trang.");
        }
        return selected;
    }

    private static String format(int width, int height) {
        return width + " × " + height + " cm";
    }
}
