// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.dsl;

/*
 * DSL for yahcli's `--payer` option.
 */
public enum Payer {
    SHORT_OPT("-p"),
    LONG_OPT("--payer");

    private final String option;

    Payer(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
