// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Default implementation of {@link ExchangeRateInfo}.
 */
public class ExchangeRateInfoImpl implements ExchangeRateInfo {

    private final ExchangeRateSet exchangeRateSet;
    private final ExchangeRate currentRate;
    private final ExchangeRate nextRate;
    private final long expirationSeconds;

    public ExchangeRateInfoImpl(@NonNull final ExchangeRateSet exchangeRateSet) {
        this.exchangeRateSet = requireNonNull(exchangeRateSet, "exchangeRateSet must not be null");
        this.currentRate = exchangeRateSet.currentRateOrThrow();
        this.nextRate = exchangeRateSet.nextRateOrThrow();
        this.expirationSeconds = currentRate.expirationTimeOrThrow().seconds();
    }

    @NonNull
    @Override
    public ExchangeRateSet exchangeRates() {
        return exchangeRateSet;
    }

    @NonNull
    @Override
    public ExchangeRate activeRate(@NonNull Instant consensusTime) {
        return consensusTime.getEpochSecond() >= expirationSeconds ? nextRate : currentRate;
    }
}
