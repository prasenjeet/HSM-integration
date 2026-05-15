package com.example.hsm.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class HsmUtilsTest {

    @Test
    void toHex_emptyArray() {
        assertEquals("", HsmUtils.toHex(new byte[0]));
    }

    @Test
    void toHex_knownValues() {
        assertEquals("deadbeef", HsmUtils.toHex(new byte[]{(byte)0xDE,(byte)0xAD,(byte)0xBE,(byte)0xEF}));
    }

    @Test
    void fromHex_roundtrip() {
        byte[] original = {0x01, 0x23, 0x45, 0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF};
        assertArrayEquals(original, HsmUtils.fromHex(HsmUtils.toHex(original)));
    }

    @Test
    void sha256_knownHash() {
        // SHA-256 of empty string
        byte[] hash = HsmUtils.sha256(new byte[0]);
        assertEquals(32, hash.length);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HsmUtils.toHex(hash));
    }

    @Test
    void sha384_length() {
        assertEquals(48, HsmUtils.sha384("test".getBytes()).length);
    }

    @Test
    void sha512_length() {
        assertEquals(64, HsmUtils.sha512("test".getBytes()).length);
    }

    @Test
    void constantTimeEquals_equalArrays() {
        assertTrue(HsmUtils.constantTimeEquals(new byte[]{1,2,3}, new byte[]{1,2,3}));
    }

    @Test
    void constantTimeEquals_differentContent() {
        assertFalse(HsmUtils.constantTimeEquals(new byte[]{1,2,3}, new byte[]{1,2,4}));
    }

    @Test
    void constantTimeEquals_differentLength() {
        assertFalse(HsmUtils.constantTimeEquals(new byte[]{1,2,3}, new byte[]{1,2}));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 8, 16})
    void truncatedHex_shorterThanMax_noEllipsis(int len) {
        byte[] data = new byte[len];
        String result = HsmUtils.truncatedHex(data, 32);
        assertFalse(result.contains("…"));
    }

    @Test
    void truncatedHex_longerThanMax_hasEllipsis() {
        byte[] data = new byte[64];
        String result = HsmUtils.truncatedHex(data, 8);
        assertTrue(result.contains("…"));
    }
}
