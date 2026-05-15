package com.example.hsm;

import com.example.hsm.config.HsmConfig;
import com.example.hsm.crypto.AesCrypto;
import com.example.hsm.crypto.EcCrypto;
import com.example.hsm.crypto.RsaCrypto;
import com.example.hsm.keys.KeyManager;
import com.example.hsm.util.HsmUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.List;

import javax.crypto.SecretKey;

/**
 * Entry-point demonstrating end-to-end key generation and cryptographic
 * operations against a SoftHSM2 token.
 *
 * Prerequisites – run {@code scripts/init-softhsm.sh} before executing this
 * class to initialise the token.
 *
 * Runtime flags (all optional – defaults match the init script):
 *   -Dhsm.lib.path=/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
 *   -Dhsm.slot.pin=1234
 *   -Dhsm.slot.index=0
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final String SAMPLE_PLAINTEXT =
            "The quick brown fox jumps over the lazy dog – HSM demo payload";

    public static void main(String[] args) {
        log.info("=== HSM Integration Demo ===");

        HsmConfig config = HsmConfig.load();

        try (HsmManager hsm = HsmManager.open(config)) {

            KeyManager km = new KeyManager(hsm);

            printKeyInventory(km);

            demoRsa(hsm, km);
            demoEc(hsm, km);
            demoAes(hsm, km);

            printKeyInventory(km);

        } catch (HsmManager.HsmException e) {
            log.error("HSM initialisation failed – is SoftHSM2 installed and the token initialised?");
            log.error("  Run:  scripts/init-softhsm.sh");
            log.error("  Error: {}", e.getMessage(), e);
            System.exit(1);
        }

        log.info("=== Demo complete ===");
    }

    // -------------------------------------------------------------------------
    // Demo sections
    // -------------------------------------------------------------------------

    private static void demoRsa(HsmManager hsm, KeyManager km) {
        log.info("--- RSA Demo ---");
        String alias = "demo-rsa-2048";

        // Generate (or reuse) key pair
        KeyPair kp;
        if (km.exists(alias)) {
            log.info("Reusing existing RSA key '{}'", alias);
            kp = null; // will fetch sign/verify keys individually
        } else {
            kp = km.generateRsaKeyPair(alias, 2048);
        }

        byte[] data = SAMPLE_PLAINTEXT.getBytes(StandardCharsets.UTF_8);

        // Signing with PSS
        RsaCrypto rsa = new RsaCrypto(hsm);

        byte[] sigPss = rsa.sign(data, km.getPrivateKey(alias));
        log.info("RSA-PSS signature: {}", HsmUtils.truncatedHex(sigPss, 16));

        boolean validPss = rsa.verify(data, sigPss, km.getPublicKey(alias));
        log.info("RSA-PSS verify:    {}", validPss ? "OK" : "FAILED");
        assertTrue("RSA-PSS verification", validPss);

        // Tamper detection
        byte[] tampered = Arrays.copyOf(data, data.length);
        tampered[0] ^= 0xFF;
        boolean rejectedTampered = !rsa.verify(tampered, sigPss, km.getPublicKey(alias));
        log.info("Tamper detection:  {}", rejectedTampered ? "OK (rejected)" : "FAILED (accepted!)");
        assertTrue("Tamper detection", rejectedTampered);

        // RSA-OAEP encryption of a small symmetric key
        byte[] wrappedKey = "my-secret-aes-key-256-bits-here!".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted  = rsa.encrypt(wrappedKey, km.getPublicKey(alias));
        byte[] decrypted  = rsa.decrypt(encrypted,  km.getPrivateKey(alias));
        log.info("RSA-OAEP roundtrip:{}", Arrays.equals(wrappedKey, decrypted) ? "OK" : "FAILED");
        assertTrue("RSA-OAEP roundtrip", Arrays.equals(wrappedKey, decrypted));
    }

    private static void demoEc(HsmManager hsm, KeyManager km) {
        log.info("--- ECDSA Demo ---");
        String alias = "demo-ec-p256";

        if (!km.exists(alias)) {
            km.generateEcKeyPair(alias, "secp256r1");
        } else {
            log.info("Reusing existing EC key '{}'", alias);
        }

        byte[] data = SAMPLE_PLAINTEXT.getBytes(StandardCharsets.UTF_8);
        EcCrypto ec = new EcCrypto(hsm);

        byte[] sig = ec.sign(data, km.getPrivateKey(alias));
        log.info("ECDSA signature:  {}", HsmUtils.truncatedHex(sig, 16));

        boolean valid = ec.verify(data, sig, km.getPublicKey(alias));
        log.info("ECDSA verify:     {}", valid ? "OK" : "FAILED");
        assertTrue("ECDSA verification", valid);

        // SHA-384 variant
        String alias384 = "demo-ec-p384";
        if (!km.exists(alias384)) {
            km.generateEcKeyPair(alias384, "secp384r1");
        }
        byte[] sig384 = ec.sign(data, km.getPrivateKey(alias384), EcCrypto.ALG_ECDSA_SHA384);
        boolean valid384 = ec.verify(data, sig384, km.getPublicKey(alias384), EcCrypto.ALG_ECDSA_SHA384);
        log.info("ECDSA-384 verify: {}", valid384 ? "OK" : "FAILED");
        assertTrue("ECDSA-384 verification", valid384);
    }

    private static void demoAes(HsmManager hsm, KeyManager km) {
        log.info("--- AES Demo ---");
        String gcmAlias = "demo-aes-gcm-256";
        String cbcAlias = "demo-aes-cbc-128";

        if (!km.exists(gcmAlias)) km.generateAesKey(gcmAlias, 256);
        if (!km.exists(cbcAlias)) km.generateAesKey(cbcAlias, 128);

        AesCrypto aes = new AesCrypto(hsm);
        byte[] data   = SAMPLE_PLAINTEXT.getBytes(StandardCharsets.UTF_8);
        byte[] aad    = "demo-context-metadata".getBytes(StandardCharsets.UTF_8);

        // AES-GCM with AAD
        SecretKey gcmKey   = km.getSecretKey(gcmAlias);
        byte[]    gcmCt    = aes.encryptGcm(data, gcmKey, aad);
        byte[]    gcmPlain = aes.decryptGcm(gcmCt, gcmKey, aad);
        log.info("AES-GCM roundtrip:{}", Arrays.equals(data, gcmPlain) ? "OK" : "FAILED");
        assertTrue("AES-GCM roundtrip", Arrays.equals(data, gcmPlain));

        // Verify wrong AAD causes authentication failure
        try {
            aes.decryptGcm(gcmCt, gcmKey, "wrong-aad".getBytes(StandardCharsets.UTF_8));
            log.error("AES-GCM AAD check: FAILED (should have thrown)");
        } catch (AesCrypto.CryptoException e) {
            log.info("AES-GCM AAD check: OK (rejected wrong AAD)");
        }

        // AES-CBC
        SecretKey cbcKey   = km.getSecretKey(cbcAlias);
        byte[]    cbcCt    = aes.encryptCbc(data, cbcKey);
        byte[]    cbcPlain = aes.decryptCbc(cbcCt, cbcKey);
        log.info("AES-CBC roundtrip:{}", Arrays.equals(data, cbcPlain) ? "OK" : "FAILED");
        assertTrue("AES-CBC roundtrip", Arrays.equals(data, cbcPlain));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void printKeyInventory(KeyManager km) {
        List<String> aliases = km.listAliases();
        log.info("HSM token inventory ({} keys): {}", aliases.size(), aliases);
    }

    private static void assertTrue(String label, boolean condition) {
        if (!condition) throw new IllegalStateException("Demo assertion failed: " + label);
    }
}
