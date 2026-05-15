package com.example.hsm;

import com.example.hsm.config.HsmConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateException;

/**
 * Manages the lifecycle of the SunPKCS11 provider backed by SoftHSM2 (or any
 * PKCS#11-compliant HSM library).
 *
 * Usage:
 * <pre>
 *   try (HsmManager hsm = HsmManager.open(HsmConfig.load())) {
 *       KeyStore ks = hsm.getKeyStore();
 *       ...
 *   }
 * </pre>
 *
 * Thread-safety: once {@link #open} returns, the underlying Provider and
 * KeyStore are immutable; methods on this class are thread-safe.
 */
public class HsmManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HsmManager.class);

    private final Provider  provider;
    private final KeyStore  keyStore;
    private final HsmConfig config;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /** Package/subclass visible constructor – used by test stubs. */
    protected HsmManager(Provider provider, KeyStore keyStore, HsmConfig config) {
        this.provider = provider;
        this.keyStore = keyStore;
        this.config   = config;
    }

    /**
     * Initialises the PKCS#11 provider, logs in to the HSM token, and loads the
     * KeyStore.  The caller is responsible for closing this manager.
     *
     * @param config fully-resolved HSM configuration
     * @return an open, authenticated HsmManager
     * @throws HsmException if the provider cannot be loaded or login fails
     */
    public static HsmManager open(HsmConfig config) {
        log.info("Opening HSM connection: library={}", config.getLibraryPath());

        Provider provider = loadProvider(config);
        KeyStore keyStore = loginAndLoadKeyStore(provider, config);

        log.info("HSM ready – provider={}", provider.getName());
        return new HsmManager(provider, keyStore, config);  // direct ctor – not subclassable path
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** The registered SunPKCS11 provider. Pass this to Cipher/Signature/etc. */
    public Provider getProvider() { return provider; }

    /**
     * The PKCS#11 KeyStore.  Keys generated via this KeyStore are created inside
     * the HSM boundary and (by default) are never extractable.
     */
    public KeyStore getKeyStore() { return keyStore; }

    public HsmConfig getConfig()  { return config; }

    // -------------------------------------------------------------------------
    // AutoCloseable
    // -------------------------------------------------------------------------

    /**
     * Logs out of the HSM token and removes the provider from the JVM security
     * provider list.  Idempotent.
     */
    @Override
    public void close() {
        try {
            keyStore.load(null, null);   // triggers PKCS#11 C_Finalize on some impls
        } catch (Exception ignored) {
            // Best-effort; the KeyStore may already be invalid
        }
        Security.removeProvider(provider.getName());
        log.info("HSM connection closed, provider '{}' removed", provider.getName());
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static Provider loadProvider(HsmConfig config) {
        try {
            Path cfgFile = config.writeTempConfigFile();

            // SunPKCS11 is built into JDK 9+; configure() returns a new instance
            Provider base = Security.getProvider("SunPKCS11");
            if (base == null) {
                throw new HsmException("SunPKCS11 provider not found – requires JDK 9+");
            }
            Provider p = base.configure(cfgFile.toAbsolutePath().toString());
            Security.addProvider(p);
            log.debug("Registered provider: {}", p.getName());
            return p;

        } catch (IOException e) {
            throw new HsmException("Failed to write PKCS#11 config file", e);
        }
    }

    private static KeyStore loginAndLoadKeyStore(Provider provider, HsmConfig config) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS11", provider);
            // Passing the PIN here performs C_Login (User role) automatically
            ks.load(null, config.getUserPinChars());
            log.debug("KeyStore loaded, {} entries present", ks.size());
            return ks;
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
            Security.removeProvider(provider.getName());
            throw new HsmException("Failed to load PKCS#11 KeyStore – check PIN and slot", e);
        }
    }

    // -------------------------------------------------------------------------
    // Nested exception type
    // -------------------------------------------------------------------------

    public static final class HsmException extends RuntimeException {
        public HsmException(String message)                   { super(message); }
        public HsmException(String message, Throwable cause)  { super(message, cause); }
    }
}
