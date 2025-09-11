// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts.dsl;

/*
 * DSL for accounts command's `--gen-new-key` option.
 */
public enum GenNewKey {
    SHORT_OPT("-g"),
    LONG_OPT("--gen-new-key");

    private final String option;

    GenNewKey(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
