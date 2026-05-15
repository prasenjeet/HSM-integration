package com.example.hsm.keys;

import com.example.hsm.HsmManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * High-level key management operations on the HSM:
 * <ul>
 *   <li>RSA key-pair generation (2048 / 3072 / 4096 bit)</li>
 *   <li>EC key-pair generation (P-256, P-384, P-521)</li>
 *   <li>AES secret-key generation (128 / 192 / 256 bit)</li>
 *   <li>Listing and deleting keys by alias</li>
 * </ul>
 *
 * All generated keys are stored as token objects (persistent inside the HSM)
 * and, by the PKCS#11 config, are marked non-extractable and sensitive.
 */
public final class KeyManager {

    private static final Logger log = LoggerFactory.getLogger(KeyManager.class);

    private final HsmManager hsm;

    public KeyManager(HsmManager hsm) {
        this.hsm = hsm;
    }

    // -------------------------------------------------------------------------
    // RSA
    // -------------------------------------------------------------------------

    /**
     * Generates an RSA key pair inside the HSM and stores it under {@code alias}.
     *
     * @param alias    unique identifier stored in the PKCS#11 CKA_LABEL
     * @param keySize  key size in bits; typical values: 2048, 3072, 4096
     * @return the generated key pair (private key is an HSM reference – not extractable)
     */
    public KeyPair generateRsaKeyPair(String alias, int keySize) {
        log.info("Generating RSA-{} key pair with alias '{}'", keySize, alias);
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", hsm.getProvider());
            kpg.initialize(keySize, new SecureRandom());
            KeyPair kp = kpg.generateKeyPair();
            persistKeyPair(alias, kp);
            log.info("RSA key pair '{}' generated and stored in HSM", alias);
            return kp;
        } catch (GeneralSecurityException e) {
            throw new KeyManagementException("RSA key generation failed for alias: " + alias, e);
        }
    }

    // -------------------------------------------------------------------------
    // EC
    // -------------------------------------------------------------------------

    /**
     * Generates an EC key pair using a named curve.
     *
     * @param alias     unique label
     * @param curveName standard curve name, e.g. "secp256r1", "secp384r1", "secp521r1"
     */
    public KeyPair generateEcKeyPair(String alias, String curveName) {
        log.info("Generating EC key pair (curve={}) with alias '{}'", curveName, alias);
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", hsm.getProvider());
            kpg.initialize(new ECGenParameterSpec(curveName), new SecureRandom());
            KeyPair kp = kpg.generateKeyPair();
            persistKeyPair(alias, kp);
            log.info("EC key pair '{}' generated and stored in HSM", alias);
            return kp;
        } catch (GeneralSecurityException e) {
            throw new KeyManagementException("EC key generation failed for alias: " + alias, e);
        }
    }

    // -------------------------------------------------------------------------
    // AES
    // -------------------------------------------------------------------------

    /**
     * Generates an AES secret key inside the HSM.
     *
     * @param alias   unique label
     * @param keySize key size in bits: 128, 192, or 256
     * @return the generated SecretKey (HSM reference)
     */
    public SecretKey generateAesKey(String alias, int keySize) {
        log.info("Generating AES-{} key with alias '{}'", keySize, alias);
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES", hsm.getProvider());
            kg.init(keySize, new SecureRandom());
            SecretKey sk = kg.generateKey();
            // Store in KeyStore so it persists across sessions
            hsm.getKeyStore().setKeyEntry(alias, sk, hsm.getConfig().getUserPinChars(), null);
            log.info("AES key '{}' generated and stored in HSM", alias);
            return sk;
        } catch (GeneralSecurityException e) {
            throw new KeyManagementException("AES key generation failed for alias: " + alias, e);
        }
    }

    // -------------------------------------------------------------------------
    // Key retrieval
    // -------------------------------------------------------------------------

    /**
     * Retrieves a private key by alias.  The returned object is a PKCS#11
     * hardware reference; cryptographic operations with it happen inside the HSM.
     */
    public PrivateKey getPrivateKey(String alias) {
        try {
            Key key = hsm.getKeyStore().getKey(alias, hsm.getConfig().getUserPinChars());
            if (key instanceof PrivateKey pk) return pk;
            throw new KeyManagementException("No private key found for alias: " + alias);
        } catch (GeneralSecurityException e) {
            throw new KeyManagementException("Failed to retrieve private key: " + alias, e);
        }
    }

    /** Retrieves the public key certificate entry's public key by alias. */
    public PublicKey getPublicKey(String alias) {
        try {
            java.security.cert.Certificate cert = hsm.getKeyStore().getCertificate(alias);
            if (cert != null) return cert.getPublicKey();
            // Some HSM slots don't store certs; fall back to KeyPair approach
            throw new KeyManagementException("No certificate/public key found for alias: " + alias);
        } catch (KeyStoreException e) {
            throw new KeyManagementException("Failed to retrieve public key: " + alias, e);
        }
    }

    /** Retrieves a secret key (AES etc.) by alias. */
    public SecretKey getSecretKey(String alias) {
        try {
            Key key = hsm.getKeyStore().getKey(alias, hsm.getConfig().getUserPinChars());
            if (key instanceof SecretKey sk) return sk;
            throw new KeyManagementException("No secret key found for alias: " + alias);
        } catch (GeneralSecurityException e) {
            throw new KeyManagementException("Failed to retrieve secret key: " + alias, e);
        }
    }

    // -------------------------------------------------------------------------
    // Listing and deletion
    // -------------------------------------------------------------------------

    /** Returns all key aliases currently stored on the token. */
    public List<String> listAliases() {
        try {
            List<String> result = new ArrayList<>();
            Enumeration<String> aliases = hsm.getKeyStore().aliases();
            while (aliases.hasMoreElements()) result.add(aliases.nextElement());
            return Collections.unmodifiableList(result);
        } catch (KeyStoreException e) {
            throw new KeyManagementException("Failed to list key aliases", e);
        }
    }

    /** Returns true if an entry with the given alias exists on the token. */
    public boolean exists(String alias) {
        try {
            return hsm.getKeyStore().containsAlias(alias);
        } catch (KeyStoreException e) {
            throw new KeyManagementException("Failed to check alias existence: " + alias, e);
        }
    }

    /**
     * Permanently deletes a key entry from the HSM token.
     * This operation is irreversible – use with care.
     */
    public void deleteKey(String alias) {
        log.warn("Deleting HSM key alias '{}'", alias);
        try {
            hsm.getKeyStore().deleteEntry(alias);
            log.info("Key '{}' deleted from HSM", alias);
        } catch (KeyStoreException e) {
            throw new KeyManagementException("Failed to delete key: " + alias, e);
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void persistKeyPair(String alias, KeyPair kp) throws KeyStoreException {
        // PKCS#11 KeyStore stores asymmetric keys as KeyPair entries (no cert required
        // for private key storage in token objects, but we supply null cert chain here).
        hsm.getKeyStore().setKeyEntry(
                alias,
                kp.getPrivate(),
                hsm.getConfig().getUserPinChars(),
                null);
    }

    // -------------------------------------------------------------------------
    // Nested exception type
    // -------------------------------------------------------------------------

    public static final class KeyManagementException extends RuntimeException {
        public KeyManagementException(String msg)                   { super(msg); }
        public KeyManagementException(String msg, Throwable cause)  { super(msg, cause); }
    }
}
