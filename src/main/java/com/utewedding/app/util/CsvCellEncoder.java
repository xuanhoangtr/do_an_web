package com.utewedding.app.util;

/** CSV cell quoting with protection against spreadsheet formula evaluation. */
public final class CsvCellEncoder {
    private CsvCellEncoder() { }

    public static String cell(String value) {
        String safe = value == null ? "" : value;
        int first = 0;
        while (first < safe.length()) {
            char character = safe.charAt(first);
            if (character == '\t' || character == '\r') break;
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)
                    && character != '\uFEFF') break;
            first++;
        }
        if (first < safe.length() && "=+-@\t\r".indexOf(safe.charAt(first)) >= 0) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public static String row(String... values) {
        return java.util.Arrays.stream(values).map(CsvCellEncoder::cell)
                .collect(java.util.stream.Collectors.joining(","));
    }
}
