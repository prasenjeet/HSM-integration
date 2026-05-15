package com.example.hsm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * Holds all tuneable parameters for the PKCS#11 / HSM connection.
 *
 * Resolution order for every value (first wins):
 *   1. System property (-Dhsm.xxx=…)
 *   2. Environment variable (HSM_XXX)
 *   3. classpath resource hsm.properties
 *   4. Hard-coded default
 */
public final class HsmConfig {

    private static final Logger log = LoggerFactory.getLogger(HsmConfig.class);

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------
    private static final String DEFAULT_LIB_LINUX   = "/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so";
    private static final String DEFAULT_LIB_MAC_BREW = "/opt/homebrew/lib/softhsm/libsofthsm2.so";
    private static final String DEFAULT_PIN          = "1234";
    private static final int    DEFAULT_SLOT_INDEX   = 0;

    // -------------------------------------------------------------------------
    // Resolved configuration
    // -------------------------------------------------------------------------
    private final String libraryPath;
    private final String userPin;
    private final int    slotListIndex;
    private final String providerName;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    private HsmConfig(String libraryPath, String userPin, int slotListIndex, String providerName) {
        this.libraryPath   = libraryPath;
        this.userPin       = userPin;
        this.slotListIndex = slotListIndex;
        this.providerName  = providerName;
    }

    /** Load config from system properties / env / classpath defaults. */
    public static HsmConfig load() {
        Properties props = loadClasspathProperties();

        String lib = resolve("hsm.lib.path", "HSM_LIB_PATH", props, detectDefaultLib());
        String pin = resolve("hsm.slot.pin",  "HSM_SLOT_PIN",  props, DEFAULT_PIN);
        int slot   = Integer.parseInt(resolve("hsm.slot.index", "HSM_SLOT_INDEX", props,
                String.valueOf(DEFAULT_SLOT_INDEX)));
        String providerName = resolve("hsm.provider.name", "HSM_PROVIDER_NAME", props, "SoftHSM2");

        log.info("HSM config: library={}, slotIndex={}, provider={}", lib, slot, providerName);
        return new HsmConfig(lib, pin, slot, providerName);
    }

    /** Create a config pointing at an explicit library (useful in tests / CI). */
    public static HsmConfig of(String libraryPath, String userPin, int slotIndex) {
        return new HsmConfig(libraryPath, userPin, slotIndex, "SoftHSM2");
    }

    // -------------------------------------------------------------------------
    // PKCS#11 config-file generation
    // -------------------------------------------------------------------------

    /**
     * Writes a temporary PKCS#11 config file accepted by {@code SunPKCS11.configure()}.
     * The JDK provider expects a file path, not an InputStream, in Java 9+.
     */
    public Path writeTempConfigFile() throws IOException {
        String content = String.format(
                "name = %s%n" +
                "library = %s%n" +
                "slotListIndex = %d%n",
                providerName, libraryPath, slotListIndex);

        Path tmp = Files.createTempFile("pkcs11-", ".cfg");
        tmp.toFile().deleteOnExit();
        Files.writeString(tmp, content, StandardOpenOption.TRUNCATE_EXISTING);
        log.debug("Wrote PKCS#11 config to {}", tmp);
        return tmp;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getLibraryPath()  { return libraryPath;   }
    public String getUserPin()      { return userPin;        }
    public int    getSlotListIndex(){ return slotListIndex;  }
    public String getProviderName() { return providerName;   }
    public char[] getUserPinChars() { return userPin.toCharArray(); }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String resolve(String sysProp, String envVar, Properties props, String fallback) {
        String v = System.getProperty(sysProp);
        if (v != null && !v.isEmpty()) return v;
        v = System.getenv(envVar);
        if (v != null && !v.isEmpty()) return v;
        v = props.getProperty(sysProp);
        if (v != null && !v.isEmpty()) return v;
        return fallback;
    }

    private static Properties loadClasspathProperties() {
        Properties p = new Properties();
        try (InputStream in = HsmConfig.class.getResourceAsStream("/hsm.properties")) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            log.debug("No hsm.properties on classpath – using defaults");
        }
        return p;
    }

    private static String detectDefaultLib() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            // Prefer Apple-Silicon brew path; fall back to Intel
            if (Path.of(DEFAULT_LIB_MAC_BREW).toFile().exists()) return DEFAULT_LIB_MAC_BREW;
            return "/usr/local/lib/softhsm/libsofthsm2.so";
        }
        return DEFAULT_LIB_LINUX;
    }
}
