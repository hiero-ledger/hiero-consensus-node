// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import edu.umd.cs.findbugs.annotations.NonNull;

@ConfigData("accounts")
public record AccountsConfig(
        @ConfigProperty(defaultValue = "54") @NetworkProperty long softwareUpdateAdmin,
        @ConfigProperty(defaultValue = "55") @NetworkProperty long addressBookAdmin,
        @ConfigProperty(defaultValue = "57") @NetworkProperty long exchangeRatesAdmin,
        @ConfigProperty(defaultValue = "56") @NetworkProperty long feeSchedulesAdmin,
        @ConfigProperty(defaultValue = "58") @NetworkProperty long freezeAdmin,
        @ConfigProperty(defaultValue = "100") @NetworkProperty long lastThrottleExempt,
        @ConfigProperty(defaultValue = "801") @NetworkProperty long nodeRewardAccount,
        @ConfigProperty(defaultValue = "800") @NetworkProperty long stakingRewardAccount,
        @ConfigProperty(defaultValue = "50") @NetworkProperty long systemAdmin,
        @ConfigProperty(defaultValue = "59") @NetworkProperty long systemDeleteAdmin,
        @ConfigProperty(defaultValue = "60") @NetworkProperty long systemUndeleteAdmin,
        @ConfigProperty(defaultValue = "2") @NetworkProperty long treasury,
        @ConfigProperty(defaultValue = "100000000") @NetworkProperty long maxNumber,
        @ConfigProperty(value = "blocklist.enabled", defaultValue = "false") @NetworkProperty boolean blocklistEnabled,
        @ConfigProperty(value = "blocklist.path", defaultValue = "") @NetworkProperty String blocklistResource) {

    /**
     * Check if the given account is a superuser.
     * @param accountId the account to check
     * @return true if the account is a superuser, false otherwise
     */
    public boolean isSuperuser(@NonNull final AccountID accountId) {
        requireNonNull(accountId);
        final var accountNum = accountId.accountNumOrElse(0L);
        return accountNum == treasury || accountNum == systemAdmin;
    }
}
