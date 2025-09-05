// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.dsl;

/*
 * DSL for yahcli's `--fixed-fee` option.
 */
public enum FixedFee {
    SHORT_OPT("-f"),
    LONG_OPT("--fixed-fee");

    private final String option;

    FixedFee(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
