// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts.dsl;

/*
 * DSL for accounts command's `--keyType` option.
 */
public enum KeyType {
    SHORT_OPT("-K"),
    LONG_OPT("--keyType");

    private final String option;

    KeyType(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
