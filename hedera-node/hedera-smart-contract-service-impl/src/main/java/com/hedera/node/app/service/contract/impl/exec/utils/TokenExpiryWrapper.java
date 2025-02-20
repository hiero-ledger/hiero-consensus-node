// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import java.util.Objects;

public class TokenExpiryWrapper {
    private final long second;
    private AccountID autoRenewAccount;
    private Duration autoRenewPeriod;

    public TokenExpiryWrapper(final long second, final AccountID autoRenewAccount, final Duration autoRenewPeriod) {
        this.second = second;
        this.autoRenewAccount = autoRenewAccount;
        this.autoRenewPeriod = autoRenewPeriod;
    }

    public long second() {
        return second;
    }

    public AccountID autoRenewAccount() {
        return autoRenewAccount;
    }

    public void setAutoRenewAccount(final AccountID autoRenewAccount) {
        this.autoRenewAccount = autoRenewAccount;
    }

    public Duration autoRenewPeriod() {
        return autoRenewPeriod;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TokenExpiryWrapper that = (TokenExpiryWrapper) o;
        return second == that.second
                && autoRenewPeriod == that.autoRenewPeriod
                && Objects.equals(autoRenewAccount, that.autoRenewAccount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(second, autoRenewAccount, autoRenewPeriod);
    }

    @Override
    public String toString() {
        return "TokenExpiryWrapper{"
                + "second="
                + second
                + ", autoRenewAccount="
                + autoRenewAccount
                + ", autoRenewPeriod="
                + autoRenewPeriod
                + '}';
    }
}
