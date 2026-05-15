package com.example.hsm.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AesCryptoTest {

    private static Provider bcProvider;
    private static SecretKey aes256Key;
    private static SecretKey aes128Key;

    private AesCrypto aesCrypto;

    @BeforeAll
    static void setup() throws Exception {
        bcProvider = new BouncyCastleProvider();
        Security.addProvider(bcProvider);

        KeyGenerator kg = KeyGenerator.getInstance("AES", bcProvider);
        kg.init(256);
        aes256Key = kg.generateKey();
        kg.init(128);
        aes128Key = kg.generateKey();
    }

    @AfterAll
    static void teardown() {
        Security.removeProvider(bcProvider.getName());
    }

    @BeforeEach
    void init() {
        aesCrypto = new AesCrypto(new FakeHsmManager(bcProvider));
    }

    // -------------------------------------------------------------------------
    // AES-GCM
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "exactly 16 bytes", "this is a longer message for AES-GCM testing"})
    @Order(1)
    void gcm_roundtrip(String plaintext) {
        byte[] plain = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] ct    = aesCrypto.encryptGcm(plain, aes256Key);
        byte[] dec   = aesCrypto.decryptGcm(ct, aes256Key);
        assertArrayEquals(plain, dec, "AES-GCM roundtrip for: " + plaintext);
    }

    @Test
    @Order(2)
    void gcm_roundtrip_withAad() {
        byte[] plain = "secret message".getBytes(StandardCharsets.UTF_8);
        byte[] aad   = "context header".getBytes(StandardCharsets.UTF_8);
        byte[] ct    = aesCrypto.encryptGcm(plain, aes256Key, aad);
        byte[] dec   = aesCrypto.decryptGcm(ct, aes256Key, aad);
        assertArrayEquals(plain, dec);
    }

    @Test
    @Order(3)
    void gcm_wrongAad_throwsCryptoException() {
        byte[] plain    = "data".getBytes(StandardCharsets.UTF_8);
        byte[] aad      = "correct".getBytes(StandardCharsets.UTF_8);
        byte[] wrongAad = "incorrect".getBytes(StandardCharsets.UTF_8);
        byte[] ct = aesCrypto.encryptGcm(plain, aes256Key, aad);
        assertThrows(AesCrypto.CryptoException.class, () -> aesCrypto.decryptGcm(ct, aes256Key, wrongAad));
    }

    @Test
    @Order(4)
    void gcm_tamperedCiphertext_throwsCryptoException() {
        byte[] plain = "sensitive data".getBytes(StandardCharsets.UTF_8);
        byte[] ct    = aesCrypto.encryptGcm(plain, aes256Key);
        ct[ct.length - 1] ^= 0xFF;   // corrupt auth tag
        assertThrows(AesCrypto.CryptoException.class, () -> aesCrypto.decryptGcm(ct, aes256Key));
    }

    @Test
    @Order(5)
    void gcm_ivIsRandomised_noDuplicateCiphertexts() {
        byte[] plain = "repeated plaintext".getBytes(StandardCharsets.UTF_8);
        byte[] ct1   = aesCrypto.encryptGcm(plain, aes256Key);
        byte[] ct2   = aesCrypto.encryptGcm(plain, aes256Key);
        // Different IVs → different ciphertexts
        assertFalse(java.util.Arrays.equals(ct1, ct2));
    }

    @Test
    @Order(6)
    void gcm_outputLengthIsPlainPlusIvPlusTag() {
        byte[] plain = "test".getBytes(StandardCharsets.UTF_8);
        byte[] ct    = aesCrypto.encryptGcm(plain, aes256Key);
        int expected = AesCrypto.GCM_IV_LEN + plain.length + (AesCrypto.GCM_TAG_BITS / 8);
        assertEquals(expected, ct.length);
    }

    // -------------------------------------------------------------------------
    // AES-CBC
    // -------------------------------------------------------------------------

    @Test
    @Order(7)
    void cbc_roundtrip_aes256() {
        byte[] plain = "AES-CBC test data".getBytes(StandardCharsets.UTF_8);
        byte[] ct    = aesCrypto.encryptCbc(plain, aes256Key);
        byte[] dec   = aesCrypto.decryptCbc(ct, aes256Key);
        assertArrayEquals(plain, dec);
    }

    @Test
    @Order(8)
    void cbc_roundtrip_aes128() {
        byte[] plain = "128-bit key AES".getBytes(StandardCharsets.UTF_8);
        byte[] ct    = aesCrypto.encryptCbc(plain, aes128Key);
        byte[] dec   = aesCrypto.decryptCbc(ct, aes128Key);
        assertArrayEquals(plain, dec);
    }

    @Test
    @Order(9)
    void cbc_ivIsRandomised() {
        byte[] plain = "same data".getBytes(StandardCharsets.UTF_8);
        byte[] ct1   = aesCrypto.encryptCbc(plain, aes256Key);
        byte[] ct2   = aesCrypto.encryptCbc(plain, aes256Key);
        assertFalse(java.util.Arrays.equals(ct1, ct2));
    }
}
