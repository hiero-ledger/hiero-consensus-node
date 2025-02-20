// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.validation;

import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Encapsulates expiry/auto-renewal metadata for an entity.
 *
 * <p>Is <b>not</b> required to be a valid combination of values; so it can,
 * for example, be used to represent an invalid entity creation attempt.
 *
 * @param expiry the consensus second at which the entity expires
 * @param autoRenewPeriod the number of seconds between auto-renewals
 * @param autoRenewAccountId the id of the account to be charged for auto-renewals
 */
public record ExpiryMeta(long expiry, long autoRenewPeriod, @Nullable AccountID autoRenewAccountId) {

    /**
     * A sentinel value indicating some part of the metadata is not available..
     */
    public static long NA = Long.MIN_VALUE;

    /**
     * Returns true if the expiry is explicitly set.
     *
     * @return whether this metadata has explicit expiry
     */
    public boolean hasExplicitExpiry() {
        return expiry != NA;
    }

    /**
     * Returns true if the auto-renew period is explicitly set.
     *
     * @return whether this metadata has explicit auto-renew period
     */
    public boolean hasAutoRenewPeriod() {
        return autoRenewPeriod != NA;
    }

    /**
     * Returns true if the auto-renew account is explicitly set.
     *
     * @return whether this metadata has explicit auto-renew account
     */
    public boolean hasAutoRenewAccountId() {
        return autoRenewAccountId != null;
    }

    /**
     * Returns true if the auto-renew period and account are both explicitly set.
     *
     * @return whether this metadata has explicit auto-renew period and account
     */
    public boolean hasFullAutoRenewSpec() {
        return hasAutoRenewAccountId() && hasAutoRenewPeriod();
    }
}
