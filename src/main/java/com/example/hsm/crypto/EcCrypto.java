package com.example.hsm.crypto;

import com.example.hsm.HsmManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.*;

/**
 * Elliptic-Curve digital-signature operations using HSM-resident EC keys.
 *
 * <ul>
 *   <li>ECDSA with SHA-256, SHA-384, or SHA-512 digest</li>
 *   <li>Signature output is DER-encoded (ASN.1 SEQUENCE of two INTEGERs r, s)</li>
 * </ul>
 *
 * ECDH key-agreement is intentionally excluded here because the raw shared
 * secret returned by ECDH needs careful handling (key-derivation step) and is
 * out of scope for this sample.
 */
public final class EcCrypto {

    private static final Logger log = LoggerFactory.getLogger(EcCrypto.class);

    public static final String ALG_ECDSA_SHA256 = "SHA256withECDSA";
    public static final String ALG_ECDSA_SHA384 = "SHA384withECDSA";
    public static final String ALG_ECDSA_SHA512 = "SHA512withECDSA";

    private final HsmManager hsm;

    public EcCrypto(HsmManager hsm) {
        this.hsm = hsm;
    }

    // -------------------------------------------------------------------------
    // Signing
    // -------------------------------------------------------------------------

    /**
     * Signs {@code data} with the HSM-resident EC private key.
     *
     * @param data       raw bytes (hashed inside the Signature engine)
     * @param privateKey HSM reference to the EC private key
     * @param algorithm  e.g. {@link #ALG_ECDSA_SHA256}
     * @return DER-encoded ECDSA signature
     */
    public byte[] sign(byte[] data, PrivateKey privateKey, String algorithm) {
        log.debug("ECDSA sign: algorithm={}, dataLen={}", algorithm, data.length);
        try {
            Signature sig = Signature.getInstance(algorithm, hsm.getProvider());
            sig.initSign(privateKey);
            sig.update(data);
            byte[] signature = sig.sign();
            log.debug("ECDSA signature: {} bytes", signature.length);
            return signature;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("ECDSA sign failed", e);
        }
    }

    /** Convenience overload – defaults to SHA-256 digest. */
    public byte[] sign(byte[] data, PrivateKey privateKey) {
        return sign(data, privateKey, ALG_ECDSA_SHA256);
    }

    // -------------------------------------------------------------------------
    // Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies a DER-encoded ECDSA signature.
     *
     * @param data      original data
     * @param signature DER-encoded signature produced by {@link #sign}
     * @param publicKey EC public key (HSM reference or plain JCE key)
     * @param algorithm matching algorithm used during signing
     * @return true if the signature is valid
     */
    public boolean verify(byte[] data, byte[] signature, PublicKey publicKey, String algorithm) {
        log.debug("ECDSA verify: algorithm={}", algorithm);
        try {
            Signature sig = Signature.getInstance(algorithm, hsm.getProvider());
            sig.initVerify(publicKey);
            sig.update(data);
            boolean valid = sig.verify(signature);
            log.debug("ECDSA signature valid: {}", valid);
            return valid;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("ECDSA verify failed", e);
        }
    }

    /** Convenience overload – defaults to SHA-256 digest. */
    public boolean verify(byte[] data, byte[] signature, PublicKey publicKey) {
        return verify(data, signature, publicKey, ALG_ECDSA_SHA256);
    }

    // -------------------------------------------------------------------------
    // Nested exception
    // -------------------------------------------------------------------------

    public static final class CryptoException extends RuntimeException {
        public CryptoException(String msg, Throwable cause) { super(msg, cause); }
    }
}
