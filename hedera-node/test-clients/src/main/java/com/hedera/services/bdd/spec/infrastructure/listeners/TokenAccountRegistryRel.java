// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.listeners;

public class TokenAccountRegistryRel {
    /* Names of a token and account in a spec registry */
    private final String token, account;

    public TokenAccountRegistryRel(String token, String account) {
        this.token = token;
        this.account = account;
    }

    public String getToken() {
        return token;
    }

    public String getAccount() {
        return account;
    }
}
