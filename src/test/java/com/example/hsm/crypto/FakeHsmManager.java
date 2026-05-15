package com.example.hsm.crypto;

import com.example.hsm.HsmManager;
import com.example.hsm.config.HsmConfig;

import java.security.KeyStore;
import java.security.Provider;

/**
 * Test-only stand-in for HsmManager that delegates to a supplied software
 * security provider (BouncyCastle in unit tests).  No real HSM is needed.
 */
class FakeHsmManager extends HsmManager {

    private final Provider softwareProvider;

    FakeHsmManager(Provider provider) {
        super(provider, null, null);
        this.softwareProvider = provider;
    }

    @Override
    public Provider getProvider() { return softwareProvider; }

    @Override
    public KeyStore getKeyStore()  { return null; }

    @Override
    public HsmConfig getConfig()   { return null; }

    @Override
    public void close() { /* no-op */ }
}
