package com.example.drive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
    @Test
    void validatesNames() {
        for (String s : new String[]{"", ".", "..", "a/b", "a\\b", "a\n", "e\u0301"})
            assertThrows(Exception.class, () -> Rules.name(s));
        assertEquals("résumé.pdf", Rules.name("résumé.pdf"));
    }

    @Test
    void validatesChecksums() {
        assertThrows(Exception.class, () -> Rules.checksum("bad"));
        assertEquals(44, Rules.checksum(Rules.hash("abc")).length());
    }

    @Test
    void validatesSizes() {
        assertEquals(8 * 1024 * 1024, Rules.partSize(1));
        assertTrue((5L * 1024 * 1024 * 1024 * 1024 + Rules.partSize(5L * 1024 * 1024 * 1024 * 1024) - 1) / Rules.partSize(5L * 1024 * 1024 * 1024 * 1024) <= 10000);
        assertEquals(8L * 1024 * 1024, Rules.partSize(0));
        assertEquals(1, Rules.partCount(0, Rules.partSize(0)));
        assertThrows(Exception.class, () -> Rules.partSize(-1));
    }

    @Test
    void validatesCursor() {
        assertEquals(12, Rules.cursor("12"));
        assertThrows(Exception.class, () -> Rules.cursor("-1"));
        assertThrows(Exception.class, () -> Rules.cursor("garbage"));
    }
}
