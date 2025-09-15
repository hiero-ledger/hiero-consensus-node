// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts.dsl;

/*
 * DSL for accounts command's `--replacement-key` option.
 */
public enum ReplKey {
    SHORT_OPT("-k"),
    LONG_OPT("--replacement-key");

    private final String option;

    ReplKey(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
