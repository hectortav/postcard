package io.sendme.http;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RangeParserTest {
    @Test void singleRange() { assertEquals(new RangeParser.Range(0, 1023), RangeParser.parse("bytes=0-1023", 10_000).orElseThrow()); }
    @Test void openEnded() { assertEquals(new RangeParser.Range(9000, 9999), RangeParser.parse("bytes=9000-", 10_000).orElseThrow()); }
    @Test void multiRangeHonorsFirst() { assertEquals(new RangeParser.Range(0, 99), RangeParser.parse("bytes=0-99,200-299", 10_000).orElseThrow()); }
    @Test void invalidReturnsEmpty() { assertTrue(RangeParser.parse("garbage", 10_000).isEmpty()); }
    @Test void outOfBoundsReturnsEmpty() { assertTrue(RangeParser.parse("bytes=9999-20000", 10_000).isEmpty()); }
}
