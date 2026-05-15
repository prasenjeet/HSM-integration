package com.example.hsm.crypto;

import com.example.hsm.HsmManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.security.*;

/**
 * RSA cryptographic operations performed inside the HSM.
 *
 * Signing   – RSA-PSS (SHA-256) and PKCS#1v1.5 (SHA-256)
 * Wrapping  – RSA-OAEP (SHA-256/MGF1) for encrypting small payloads or wrapping keys
 *
 * Private-key operations never leave the HSM boundary; only the raw bytes
 * of signatures and ciphertexts are returned to the caller.
 */
public final class RsaCrypto {

    private static final Logger log = LoggerFactory.getLogger(RsaCrypto.class);

    // Recommended algorithm strings accepted by SunPKCS11 + SoftHSM2
    public static final String ALG_SIGN_PSS     = "SHA256withRSA/PSS";
    public static final String ALG_SIGN_PKCS1   = "SHA256withRSA";
    public static final String ALG_ENCRYPT_OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    public static final String ALG_ENCRYPT_PKCS1= "RSA/ECB/PKCS1Padding";

    private final HsmManager hsm;

    public RsaCrypto(HsmManager hsm) {
        this.hsm = hsm;
    }

    // -------------------------------------------------------------------------
    // Signing
    // -------------------------------------------------------------------------

    /**
     * Signs {@code data} with the HSM-resident private key identified by
     * {@code privateKey}.  The signing operation executes entirely inside
     * the HSM; only the resulting signature bytes cross the boundary.
     *
     * @param data       raw bytes to sign (data is hashed inside the Signature engine)
     * @param privateKey HSM reference to the RSA private key
     * @param algorithm  e.g. {@link #ALG_SIGN_PSS} or {@link #ALG_SIGN_PKCS1}
     * @return DER-encoded signature bytes
     */
    public byte[] sign(byte[] data, PrivateKey privateKey, String algorithm) {
        log.debug("RSA sign: algorithm={}, dataLen={}", algorithm, data.length);
        try {
            Signature sig = Signature.getInstance(algorithm, hsm.getProvider());
            sig.initSign(privateKey);
            sig.update(data);
            byte[] signature = sig.sign();
            log.debug("Signature produced: {} bytes", signature.length);
            return signature;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("RSA sign failed", e);
        }
    }

    /** Convenience overload using the recommended PSS algorithm. */
    public byte[] sign(byte[] data, PrivateKey privateKey) {
        return sign(data, privateKey, ALG_SIGN_PSS);
    }

    // -------------------------------------------------------------------------
    // Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies a signature.  Verification uses the public key and runs in the
     * JVM (or optionally in the HSM if the public key is also a token object).
     *
     * @param data      original data
     * @param signature DER-encoded signature
     * @param publicKey RSA public key (may be an HSM reference or a plain JCE key)
     * @param algorithm matching algorithm used during signing
     * @return true if the signature is valid
     */
    public boolean verify(byte[] data, byte[] signature, PublicKey publicKey, String algorithm) {
        log.debug("RSA verify: algorithm={}", algorithm);
        try {
            Signature sig = Signature.getInstance(algorithm, hsm.getProvider());
            sig.initVerify(publicKey);
            sig.update(data);
            boolean valid = sig.verify(signature);
            log.debug("Signature valid: {}", valid);
            return valid;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("RSA verify failed", e);
        }
    }

    /** Convenience overload using the recommended PSS algorithm. */
    public boolean verify(byte[] data, byte[] signature, PublicKey publicKey) {
        return verify(data, signature, publicKey, ALG_SIGN_PSS);
    }

    // -------------------------------------------------------------------------
    // Encryption  (RSA is only suitable for small data / key-wrapping)
    // -------------------------------------------------------------------------

    /**
     * Encrypts {@code plaintext} with the RSA public key.
     * Suitable for encrypting symmetric keys or small (≤190 byte) payloads.
     *
     * @param plaintext  data to encrypt
     * @param publicKey  recipient's RSA public key
     * @param algorithm  e.g. {@link #ALG_ENCRYPT_OAEP}
     * @return ciphertext bytes
     */
    public byte[] encrypt(byte[] plaintext, PublicKey publicKey, String algorithm) {
        log.debug("RSA encrypt: algorithm={}, plainLen={}", algorithm, plaintext.length);
        try {
            Cipher cipher = Cipher.getInstance(algorithm, hsm.getProvider());
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("RSA encrypt failed", e);
        }
    }

    /** Convenience overload using OAEP. */
    public byte[] encrypt(byte[] plaintext, PublicKey publicKey) {
        return encrypt(plaintext, publicKey, ALG_ENCRYPT_OAEP);
    }

    /**
     * Decrypts RSA-encrypted data using the HSM-resident private key.
     * The decryption operation executes inside the HSM.
     *
     * @param ciphertext encrypted bytes
     * @param privateKey HSM reference to the RSA private key
     * @param algorithm  matching algorithm used during encryption
     * @return plaintext bytes
     */
    public byte[] decrypt(byte[] ciphertext, PrivateKey privateKey, String algorithm) {
        log.debug("RSA decrypt: algorithm={}, cipherLen={}", algorithm, ciphertext.length);
        try {
            Cipher cipher = Cipher.getInstance(algorithm, hsm.getProvider());
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("RSA decrypt failed", e);
        }
    }

    /** Convenience overload using OAEP. */
    public byte[] decrypt(byte[] ciphertext, PrivateKey privateKey) {
        return decrypt(ciphertext, privateKey, ALG_ENCRYPT_OAEP);
    }

    // -------------------------------------------------------------------------
    // Nested exception
    // -------------------------------------------------------------------------

    public static final class CryptoException extends RuntimeException {
        public CryptoException(String msg, Throwable cause) { super(msg, cause); }
    }
}
