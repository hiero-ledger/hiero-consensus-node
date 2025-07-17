package com.hedera.node.app.hapi.fees.apis;

import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.transaction.ExchangeRate;
import org.antlr.v4.runtime.misc.NotNull;

public class MockExchangeRate {

    public @NotNull ExchangeRate activeRate() {
        return new ExchangeRate(1,12,new TimestampSeconds(0));
    }
}
