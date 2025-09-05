// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.dsl;

/*
 * DSL for yahcli's `--network` option.
 */
public enum Network {
    SHORT_OPT("-n"),
    LONG_OPT("--network");

    private final String option;

    Network(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
