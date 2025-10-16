// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.transaction.ExchangeRate;
import edu.umd.cs.findbugs.annotations.NonNull;

public class MockExchangeRate {

    public @NonNull ExchangeRate activeRate() {
        return new ExchangeRate(1, 12, new TimestampSeconds(0));
    }
}
