// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts.dsl;

/*
 * DSL for accounts command's `--denomination` option.
 */
public enum Denomination {
    SHORT_OPT("-d"),
    LONG_OPT("--denomination");

    private final String option;

    Denomination(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
