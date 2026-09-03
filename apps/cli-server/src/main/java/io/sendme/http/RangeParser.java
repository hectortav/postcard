package io.sendme.http;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RangeParser {
    public record Range(long start, long end) {}
    private static final Pattern P = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    public static Optional<Range> parse(String header, long fileSize) {
        if (header == null || fileSize <= 0) return Optional.empty();
        String[] parts = header.split(",", 2);
        String first = parts[0].trim();
        Matcher m = P.matcher(first);
        if (!m.matches()) return Optional.empty();
        String s = m.group(1), e = m.group(2);
        long start, end;
        if (s.isEmpty()) { long suffix = Long.parseLong(e); if (suffix <= 0) return Optional.empty(); start = Math.max(0, fileSize - suffix); end = fileSize - 1; }
        else { start = Long.parseLong(s); end = e.isEmpty() ? fileSize - 1 : Long.parseLong(e); }
        if (start < 0 || end < start || end >= fileSize) return Optional.empty();
        return Optional.of(new Range(start, end));
    }

    private RangeParser() {}
}
