// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts.dsl;

/*
 * DSL for accounts command's `--memo` option.
 */
public enum Memo {
    LONG_OPT("--memo");

    private final String option;

    Memo(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
