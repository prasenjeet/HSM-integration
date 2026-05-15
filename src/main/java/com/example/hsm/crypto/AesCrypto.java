package com.example.hsm.crypto;

import com.example.hsm.HsmManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES symmetric-encryption operations using an HSM-resident key.
 *
 * Supported modes:
 * <ul>
 *   <li><b>AES-GCM</b> (recommended) – provides authenticated encryption;
 *       the 12-byte IV and 16-byte auth tag are prepended to the ciphertext.</li>
 *   <li><b>AES-CBC</b> – for interoperability with older systems;
 *       the 16-byte IV is prepended to the ciphertext.</li>
 * </ul>
 *
 * The {@link SecretKey} passed to every method is an HSM reference; the raw key
 * material never leaves the HSM.  Only ciphertext bytes cross the boundary.
 */
public final class AesCrypto {

    private static final Logger log = LoggerFactory.getLogger(AesCrypto.class);

    // GCM parameters
    public static final String ALG_GCM       = "AES/GCM/NoPadding";
    public static final int    GCM_IV_LEN    = 12;   // bytes
    public static final int    GCM_TAG_BITS  = 128;  // bits

    // CBC parameters
    public static final String ALG_CBC       = "AES/CBC/PKCS5Padding";
    public static final int    CBC_IV_LEN    = 16;   // bytes

    private final HsmManager  hsm;
    private final SecureRandom rng;

    public AesCrypto(HsmManager hsm) {
        this.hsm = hsm;
        this.rng = new SecureRandom();
    }

    // -------------------------------------------------------------------------
    // AES-GCM  (authenticated encryption – preferred)
    // -------------------------------------------------------------------------

    /**
     * Encrypts {@code plaintext} with AES-GCM using an HSM-resident key.
     *
     * @param plaintext  data to encrypt
     * @param key        HSM AES key reference
     * @param aad        additional authenticated data (may be empty, not null)
     * @return  {@code iv || ciphertext || authTag}  (IV is 12 bytes, tag is 16 bytes)
     */
    public byte[] encryptGcm(byte[] plaintext, SecretKey key, byte[] aad) {
        log.debug("AES-GCM encrypt: plainLen={}", plaintext.length);
        try {
            byte[] iv = generateIv(GCM_IV_LEN);
            Cipher cipher = Cipher.getInstance(ALG_GCM, hsm.getProvider());
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad.length > 0) cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plaintext);

            // Prepend IV: iv || ciphertext+tag
            byte[] result = new byte[GCM_IV_LEN + encrypted.length];
            System.arraycopy(iv,        0, result, 0,          GCM_IV_LEN);
            System.arraycopy(encrypted, 0, result, GCM_IV_LEN, encrypted.length);
            log.debug("AES-GCM encrypt done: outputLen={}", result.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-GCM encryption failed", e);
        }
    }

    /** Overload with empty AAD. */
    public byte[] encryptGcm(byte[] plaintext, SecretKey key) {
        return encryptGcm(plaintext, key, new byte[0]);
    }

    /**
     * Decrypts and authenticates AES-GCM ciphertext produced by {@link #encryptGcm}.
     *
     * @param ivAndCiphertext  {@code iv || ciphertext || authTag}
     * @param key              HSM AES key reference
     * @param aad              additional authenticated data (must match encryption)
     * @return plaintext bytes
     * @throws CryptoException if authentication tag verification fails
     */
    public byte[] decryptGcm(byte[] ivAndCiphertext, SecretKey key, byte[] aad) {
        log.debug("AES-GCM decrypt: inputLen={}", ivAndCiphertext.length);
        try {
            byte[] iv         = Arrays.copyOfRange(ivAndCiphertext, 0, GCM_IV_LEN);
            byte[] ciphertext = Arrays.copyOfRange(ivAndCiphertext, GCM_IV_LEN, ivAndCiphertext.length);

            Cipher cipher = Cipher.getInstance(ALG_GCM, hsm.getProvider());
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad.length > 0) cipher.updateAAD(aad);
            byte[] plain = cipher.doFinal(ciphertext);
            log.debug("AES-GCM decrypt done: plainLen={}", plain.length);
            return plain;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-GCM decryption failed (possible authentication failure)", e);
        }
    }

    /** Overload with empty AAD. */
    public byte[] decryptGcm(byte[] ivAndCiphertext, SecretKey key) {
        return decryptGcm(ivAndCiphertext, key, new byte[0]);
    }

    // -------------------------------------------------------------------------
    // AES-CBC  (for legacy interoperability)
    // -------------------------------------------------------------------------

    /**
     * Encrypts with AES-CBC.  The output is {@code iv || ciphertext}.
     *
     * Note: CBC provides confidentiality only; authenticate separately if needed.
     */
    public byte[] encryptCbc(byte[] plaintext, SecretKey key) {
        log.debug("AES-CBC encrypt: plainLen={}", plaintext.length);
        try {
            byte[] iv = generateIv(CBC_IV_LEN);
            Cipher cipher = Cipher.getInstance(ALG_CBC, hsm.getProvider());
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plaintext);

            byte[] result = new byte[CBC_IV_LEN + encrypted.length];
            System.arraycopy(iv,        0, result, 0,          CBC_IV_LEN);
            System.arraycopy(encrypted, 0, result, CBC_IV_LEN, encrypted.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-CBC encryption failed", e);
        }
    }

    /**
     * Decrypts AES-CBC ciphertext produced by {@link #encryptCbc}.
     *
     * @param ivAndCiphertext  {@code iv || ciphertext}
     * @param key              HSM AES key reference
     */
    public byte[] decryptCbc(byte[] ivAndCiphertext, SecretKey key) {
        log.debug("AES-CBC decrypt: inputLen={}", ivAndCiphertext.length);
        try {
            byte[] iv         = Arrays.copyOfRange(ivAndCiphertext, 0, CBC_IV_LEN);
            byte[] ciphertext = Arrays.copyOfRange(ivAndCiphertext, CBC_IV_LEN, ivAndCiphertext.length);

            Cipher cipher = Cipher.getInstance(ALG_CBC, hsm.getProvider());
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-CBC decryption failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private byte[] generateIv(int length) {
        byte[] iv = new byte[length];
        rng.nextBytes(iv);
        return iv;
    }

    // -------------------------------------------------------------------------
    // Nested exception
    // -------------------------------------------------------------------------

    public static final class CryptoException extends RuntimeException {
        public CryptoException(String msg, Throwable cause) { super(msg, cause); }
    }
}
