// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures.fees;

import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;

public class FakeFeeCalculator implements FeeCalculator {
    @Override
    @NonNull
    public FeeCalculator withResourceUsagePercent(double percent) {
        return this;
    }

    @Override
    @NonNull
    public FeeCalculator addBytesPerTransaction(long bytes) {
        return this;
    }

    @NonNull
    @Override
    public FeeCalculator addStorageBytesSeconds(long seconds) {
        return this;
    }

    @NonNull
    @Override
    public FeeCalculator addNetworkRamByteSeconds(long amount) {
        return this;
    }

    @NonNull
    public FeeCalculator addRamByteSeconds(long amount) {
        return this;
    }

    @NonNull
    public FeeCalculator addVerificationsPerTransaction(long amount) {
        return this;
    }

    @NonNull
    @Override
    public FeeCalculator addGas(final long amount) {
        return this;
    }

    @Override
    @NonNull
    public Fees calculate() {
        return new Fees(0, 0, 0);
    }

    @Override
    public long getCongestionMultiplier() {
        return 1;
    }

    @NonNull
    @Override
    public Fees legacyCalculate(@NonNull Function<SigValueObj, FeeData> callback) {
        callback.apply(new SigValueObj(0, 0, 0));
        return new Fees(0, 0, 0);
    }

    @NonNull
    public FakeFeeCalculator resetUsage() {
        return this;
    }
}
