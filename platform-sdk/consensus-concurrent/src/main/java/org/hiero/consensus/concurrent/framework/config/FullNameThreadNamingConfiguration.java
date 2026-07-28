// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.framework.config;

public class FullNameThreadNamingConfiguration
        extends AbstractThreadNamingConfiguration<FullNameThreadNamingConfiguration> {

    private String fullyFormattedThreadName;

    public FullNameThreadNamingConfiguration() {}

    public FullNameThreadNamingConfiguration(final String fullyFormattedThreadName) {
        this.fullyFormattedThreadName = fullyFormattedThreadName;
    }

    public FullNameThreadNamingConfiguration(FullNameThreadNamingConfiguration old) {
        this.fullyFormattedThreadName = old.fullyFormattedThreadName;
    }

    @Override
    public FullNameThreadNamingConfiguration copy() {
        return new FullNameThreadNamingConfiguration(this);
    }

    public void setFullyFormattedThreadName(final String fullyFormattedThreadName) {
        this.fullyFormattedThreadName = fullyFormattedThreadName;
    }

    public String getFullyFormattedThreadName() {
        return fullyFormattedThreadName;
    }

    @Override
    public String generateNextThreadName() {
        return fullyFormattedThreadName;
    }
}
