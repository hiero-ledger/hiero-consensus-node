// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;
import org.hiero.hapi.support.fees.FeeSchedule;

/**
 * A no-op implementation of {@link FeeCalculator} that always returns {@link Fees#FREE}.
 */
public class NoOpFeeCalculator implements FeeCalculator {

    public static final NoOpFeeCalculator INSTANCE = new NoOpFeeCalculator();

    @NonNull
    @Override
    public FeeCalculator withResourceUsagePercent(double percent) {
        return this;
    }

    @NonNull
    @Override
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
    @Override
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

    @NonNull
    public FeeCalculator resetUsage() {
        return this;
    }

    @Override
    @NonNull
    public FeeSchedule getSimpleFeesSchedule() {
        return FeeSchedule.DEFAULT;
    }

    @NonNull
    @Override
    public Fees legacyCalculate(@NonNull Function<SigValueObj, FeeData> callback) {
        return Fees.FREE;
    }

    @NonNull
    @Override
    public Fees calculate() {
        return Fees.FREE;
    }

    @Override
    public long getCongestionMultiplier() {
        return 1;
    }
}
