// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.framework.config;

public class UndefinedThreadNamingConfiguration
        extends AbstractThreadNamingConfiguration<UndefinedThreadNamingConfiguration> {

    public static final UndefinedThreadNamingConfiguration INSTANCE = new UndefinedThreadNamingConfiguration();

    public static <T extends ThreadNamingConfiguration<T>> T instance() {
        return (T) INSTANCE;
    }

    @Override
    public UndefinedThreadNamingConfiguration copy() {
        return this;
    }

    @Override
    public String generateNextThreadName() {
        return "<unnamed>";
    }

    @Override
    public void becomeImmutable() {
        if (!isImmutable()) {
            super.becomeImmutable();
        }
    }
}
