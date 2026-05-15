package com.example.hsm.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EcCryptoTest {

    private static Provider bcProvider;
    private static KeyPair  p256KeyPair;
    private static KeyPair  p384KeyPair;
    private static KeyPair  p521KeyPair;

    private EcCrypto ecCrypto;

    @BeforeAll
    static void setup() throws GeneralSecurityException {
        bcProvider = new BouncyCastleProvider();
        Security.addProvider(bcProvider);
        p256KeyPair = generateEcKeyPair("secp256r1");
        p384KeyPair = generateEcKeyPair("secp384r1");
        p521KeyPair = generateEcKeyPair("secp521r1");
    }

    @AfterAll
    static void teardown() {
        Security.removeProvider(bcProvider.getName());
    }

    @BeforeEach
    void init() {
        ecCrypto = new EcCrypto(new FakeHsmManager(bcProvider));
    }

    // -------------------------------------------------------------------------
    // Sign / verify
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void signVerify_p256_sha256_succeeds() {
        byte[] data = "HSM ECDSA test".getBytes(StandardCharsets.UTF_8);
        byte[] sig  = ecCrypto.sign(data, p256KeyPair.getPrivate());
        assertTrue(ecCrypto.verify(data, sig, p256KeyPair.getPublic()));
    }

    @ParameterizedTest
    @CsvSource({
        "secp384r1, SHA384withECDSA",
        "secp521r1, SHA512withECDSA"
    })
    @Order(2)
    void signVerify_differentCurvesAndAlgorithms(String curve, String alg) throws GeneralSecurityException {
        KeyPair kp  = generateEcKeyPair(curve);
        byte[]  data = ("Test with " + curve).getBytes(StandardCharsets.UTF_8);
        byte[]  sig  = ecCrypto.sign(data, kp.getPrivate(), alg);
        assertTrue(ecCrypto.verify(data, sig, kp.getPublic(), alg));
    }

    @Test
    @Order(3)
    void verify_rejectsAlteredData() {
        byte[] data    = "original".getBytes(StandardCharsets.UTF_8);
        byte[] sig     = ecCrypto.sign(data, p256KeyPair.getPrivate());
        byte[] altered = data.clone();
        altered[0] ^= 0x01;
        assertFalse(ecCrypto.verify(altered, sig, p256KeyPair.getPublic()));
    }

    @Test
    @Order(4)
    void verify_rejectsWrongKey() {
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        byte[] sig  = ecCrypto.sign(data, p256KeyPair.getPrivate());
        // p384 key – different key, different curve
        assertFalse(ecCrypto.verify(data, sig, p384KeyPair.getPublic(), EcCrypto.ALG_ECDSA_SHA256));
    }

    @Test
    @Order(5)
    void sign_isDeterministicInput_notSignature() {
        // ECDSA is non-deterministic (uses random k); two sigs of same data differ
        byte[] data = "same data".getBytes(StandardCharsets.UTF_8);
        byte[] sig1 = ecCrypto.sign(data, p256KeyPair.getPrivate());
        byte[] sig2 = ecCrypto.sign(data, p256KeyPair.getPrivate());
        // Both must be valid …
        assertTrue(ecCrypto.verify(data, sig1, p256KeyPair.getPublic()));
        assertTrue(ecCrypto.verify(data, sig2, p256KeyPair.getPublic()));
        // … but (with overwhelming probability) differ in bytes
        assertFalse(java.util.Arrays.equals(sig1, sig2));
    }

    @Test
    @Order(6)
    void sign_emptyPayload_succeeds() {
        byte[] empty = new byte[0];
        byte[] sig   = ecCrypto.sign(empty, p256KeyPair.getPrivate());
        assertTrue(ecCrypto.verify(empty, sig, p256KeyPair.getPublic()));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static KeyPair generateEcKeyPair(String curve) throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", bcProvider);
        kpg.initialize(new ECGenParameterSpec(curve));
        return kpg.generateKeyPair();
    }
}
