package com.example.hsm.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RsaCrypto using BouncyCastle as a software provider.
 * No real HSM required – HsmManager is replaced by a thin stub that registers BC.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RsaCryptoTest {

    private static Provider bcProvider;
    private static KeyPair  rsaKeyPair2048;
    private static KeyPair  rsaKeyPair4096;

    private RsaCrypto rsaCrypto;

    @BeforeAll
    static void setupProvider() throws GeneralSecurityException {
        bcProvider = new BouncyCastleProvider();
        Security.addProvider(bcProvider);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", bcProvider);
        kpg.initialize(2048);
        rsaKeyPair2048 = kpg.generateKeyPair();

        kpg.initialize(4096);
        rsaKeyPair4096 = kpg.generateKeyPair();
    }

    @AfterAll
    static void teardownProvider() {
        Security.removeProvider(bcProvider.getName());
    }

    @BeforeEach
    void setup() {
        rsaCrypto = new RsaCrypto(new FakeHsmManager(bcProvider));
    }

    // -------------------------------------------------------------------------
    // Signing / verification – PSS
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void signAndVerify_pss_succeeds() throws Exception {
        byte[] data = "Hello HSM".getBytes(StandardCharsets.UTF_8);
        byte[] sig  = rsaCrypto.sign(data, rsaKeyPair2048.getPrivate());
        assertTrue(rsaCrypto.verify(data, sig, rsaKeyPair2048.getPublic()));
    }

    @Test
    @Order(2)
    void verify_pss_rejectsAlteredData() {
        byte[] data = "Original message".getBytes(StandardCharsets.UTF_8);
        byte[] sig  = rsaCrypto.sign(data, rsaKeyPair2048.getPrivate());

        byte[] altered = data.clone();
        altered[0] ^= 0x01;
        assertFalse(rsaCrypto.verify(altered, sig, rsaKeyPair2048.getPublic()));
    }

    @Test
    @Order(3)
    void verify_pss_rejectsAlteredSignature() {
        byte[] data = "Test data".getBytes(StandardCharsets.UTF_8);
        byte[] sig  = rsaCrypto.sign(data, rsaKeyPair2048.getPrivate());
        sig[sig.length / 2] ^= 0xFF;
        assertFalse(rsaCrypto.verify(data, sig, rsaKeyPair2048.getPublic()));
    }

    @Test
    @Order(4)
    void verify_pss_rejectsWrongKey() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", bcProvider);
        kpg.initialize(2048);
        KeyPair other = kpg.generateKeyPair();

        byte[] data = "Data".getBytes(StandardCharsets.UTF_8);
        byte[] sig  = rsaCrypto.sign(data, rsaKeyPair2048.getPrivate());
        assertFalse(rsaCrypto.verify(data, sig, other.getPublic()));
    }

    // -------------------------------------------------------------------------
    // Signing / verification – PKCS#1
    // -------------------------------------------------------------------------

    @Test
    @Order(5)
    void signAndVerify_pkcs1_succeeds() {
        byte[] data = "PKCS1 test".getBytes(StandardCharsets.UTF_8);
        byte[] sig  = rsaCrypto.sign(data, rsaKeyPair2048.getPrivate(), RsaCrypto.ALG_SIGN_PKCS1);
        assertTrue(rsaCrypto.verify(data, sig, rsaKeyPair2048.getPublic(), RsaCrypto.ALG_SIGN_PKCS1));
    }

    // -------------------------------------------------------------------------
    // Encryption / decryption – OAEP
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"short", "exactly 16 bytes", "A 32-byte key material payload!!"})
    @Order(6)
    void encryptDecrypt_oaep_roundtrip(String plaintext) {
        byte[] plain = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] ct    = rsaCrypto.encrypt(plain, rsaKeyPair2048.getPublic());
        byte[] dec   = rsaCrypto.decrypt(ct, rsaKeyPair2048.getPrivate());
        assertArrayEquals(plain, dec);
    }

    @Test
    @Order(7)
    void encryptDecrypt_oaep_4096key() {
        byte[] plain = "Wrapped AES key material".getBytes(StandardCharsets.UTF_8);
        byte[] ct    = rsaCrypto.encrypt(plain, rsaKeyPair4096.getPublic());
        byte[] dec   = rsaCrypto.decrypt(ct, rsaKeyPair4096.getPrivate());
        assertArrayEquals(plain, dec);
    }

    @Test
    @Order(8)
    void encrypt_oaep_isCiphertext_differentEachTime() {
        byte[] plain = "same plaintext".getBytes(StandardCharsets.UTF_8);
        byte[] ct1 = rsaCrypto.encrypt(plain, rsaKeyPair2048.getPublic());
        byte[] ct2 = rsaCrypto.encrypt(plain, rsaKeyPair2048.getPublic());
        // OAEP uses random padding: two encryptions of the same plaintext differ
        assertFalse(java.util.Arrays.equals(ct1, ct2));
    }

}
