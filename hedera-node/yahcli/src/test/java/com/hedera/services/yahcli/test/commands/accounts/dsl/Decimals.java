// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts.dsl;

/*
 * DSL for accounts command's `--decimals` option.
 */
public enum Decimals {
    LONG_OPT("--decimals");

    private final String option;

    Decimals(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
