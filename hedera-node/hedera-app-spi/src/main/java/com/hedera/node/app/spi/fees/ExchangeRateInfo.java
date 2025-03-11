// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

public interface ExchangeRateInfo {

    /**
     * Gets the current {@link ExchangeRateSet}.
     *
     * @return The current {@link ExchangeRateSet}.
     */
    @NonNull
    ExchangeRateSet exchangeRates();

    /**
     * Gets the {@link ExchangeRate} that should be used as of the given consensus time.
     *
     * @param consensusTime The consensus time. If after the expiration time of the current rate, the next rate will
     *                      be returned. Otherwise, the current rate will be returned.
     * @return The {@link ExchangeRate} that should be used as of the given consensus time.
     */
    @NonNull
    ExchangeRate activeRate(@NonNull final Instant consensusTime);
}
