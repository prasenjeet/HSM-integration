package com.example.hsm;

import com.example.hsm.config.HsmConfig;
import com.example.hsm.crypto.AesCrypto;
import com.example.hsm.crypto.EcCrypto;
import com.example.hsm.crypto.RsaCrypto;
import com.example.hsm.keys.KeyManager;
import org.junit.jupiter.api.*;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests that require a running SoftHSM2 token.
 *
 * Skipped automatically during unit-test runs (excluded by maven-surefire-plugin).
 * To run:
 *   1. Install SoftHSM2 and initialise a token:   scripts/init-softhsm.sh
 *   2. mvn verify -Pintegration
 *        -Dhsm.lib.path=/path/to/libsofthsm2.so
 *        -Dhsm.slot.pin=1234
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HsmIntegrationTest {

    private static HsmManager  hsmManager;
    private static KeyManager  keyManager;
    private static RsaCrypto   rsaCrypto;
    private static EcCrypto    ecCrypto;
    private static AesCrypto   aesCrypto;

    private static final String RSA_ALIAS = "it-rsa-2048";
    private static final String EC_ALIAS  = "it-ec-p256";
    private static final String AES_ALIAS = "it-aes-256";

    @BeforeAll
    static void initHsm() {
        hsmManager = HsmManager.open(HsmConfig.load());
        keyManager = new KeyManager(hsmManager);
        rsaCrypto  = new RsaCrypto(hsmManager);
        ecCrypto   = new EcCrypto(hsmManager);
        aesCrypto  = new AesCrypto(hsmManager);

        // Clean slate for idempotent test runs
        for (String alias : new String[]{RSA_ALIAS, EC_ALIAS, AES_ALIAS}) {
            if (keyManager.exists(alias)) keyManager.deleteKey(alias);
        }
    }

    @AfterAll
    static void closeHsm() {
        if (hsmManager != null) hsmManager.close();
    }

    // -------------------------------------------------------------------------
    // Key generation
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void rsaKeyPairGeneratedInHsm() {
        KeyPair kp = keyManager.generateRsaKeyPair(RSA_ALIAS, 2048);
        assertNotNull(kp);
        assertTrue(keyManager.exists(RSA_ALIAS));
    }

    @Test
    @Order(2)
    void ecKeyPairGeneratedInHsm() {
        KeyPair kp = keyManager.generateEcKeyPair(EC_ALIAS, "secp256r1");
        assertNotNull(kp);
        assertTrue(keyManager.exists(EC_ALIAS));
    }

    @Test
    @Order(3)
    void aesKeyGeneratedInHsm() {
        SecretKey sk = keyManager.generateAesKey(AES_ALIAS, 256);
        assertNotNull(sk);
        assertTrue(keyManager.exists(AES_ALIAS));
    }

    // -------------------------------------------------------------------------
    // RSA operations
    // -------------------------------------------------------------------------

    @Test
    @Order(4)
    void rsaSignVerify_pss() {
        byte[] data = "Integration test data".getBytes(StandardCharsets.UTF_8);
        byte[] sig  = rsaCrypto.sign(data, keyManager.getPrivateKey(RSA_ALIAS));
        assertTrue(rsaCrypto.verify(data, sig, keyManager.getPublicKey(RSA_ALIAS)));
    }

    @Test
    @Order(5)
    void rsaEncryptDecrypt_oaep() {
        byte[] plain = "Wrap-key payload".getBytes(StandardCharsets.UTF_8);
        byte[] ct    = rsaCrypto.encrypt(plain, keyManager.getPublicKey(RSA_ALIAS));
        byte[] dec   = rsaCrypto.decrypt(ct, keyManager.getPrivateKey(RSA_ALIAS));
        assertArrayEquals(plain, dec);
    }

    // -------------------------------------------------------------------------
    // EC operations
    // -------------------------------------------------------------------------

    @Test
    @Order(6)
    void ecdsaSignVerify() {
        byte[] data = "EC integration test".getBytes(StandardCharsets.UTF_8);
        byte[] sig  = ecCrypto.sign(data, keyManager.getPrivateKey(EC_ALIAS));
        assertTrue(ecCrypto.verify(data, sig, keyManager.getPublicKey(EC_ALIAS)));
    }

    // -------------------------------------------------------------------------
    // AES operations
    // -------------------------------------------------------------------------

    @Test
    @Order(7)
    void aesGcm_roundtrip() {
        byte[] plain = "AES-GCM integration test".getBytes(StandardCharsets.UTF_8);
        byte[] aad   = "metadata".getBytes(StandardCharsets.UTF_8);
        SecretKey key = keyManager.getSecretKey(AES_ALIAS);
        byte[] ct  = aesCrypto.encryptGcm(plain, key, aad);
        byte[] dec = aesCrypto.decryptGcm(ct, key, aad);
        assertArrayEquals(plain, dec);
    }

    // -------------------------------------------------------------------------
    // Key listing / deletion
    // -------------------------------------------------------------------------

    @Test
    @Order(8)
    void keyListingContainsAllGeneratedKeys() {
        var aliases = keyManager.listAliases();
        assertTrue(aliases.contains(RSA_ALIAS));
        assertTrue(aliases.contains(EC_ALIAS));
        assertTrue(aliases.contains(AES_ALIAS));
    }

    @Test
    @Order(9)
    void keyDeletion_removesFromToken() {
        String temp = "it-temp-rsa";
        keyManager.generateRsaKeyPair(temp, 2048);
        assertTrue(keyManager.exists(temp));

        keyManager.deleteKey(temp);
        assertFalse(keyManager.exists(temp));
    }
}
