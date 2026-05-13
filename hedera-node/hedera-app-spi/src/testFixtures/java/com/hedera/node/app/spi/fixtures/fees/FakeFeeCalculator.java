// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures.fees;

import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.support.fees.FeeSchedule;

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
    public FakeFeeCalculator resetUsage() {
        return this;
    }

    @Override
    public @NonNull FeeSchedule getSimpleFeesSchedule() {
        return FeeSchedule.DEFAULT;
    }
}
