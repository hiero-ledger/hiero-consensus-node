// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts.dsl;

/*
 * DSL for accounts command's `--to` option.
 */
public enum Beneficiary {
    LONG_OPT("--to");

    private final String option;

    Beneficiary(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
