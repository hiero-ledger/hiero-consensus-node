// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.fees;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getFeeObject;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getSignatureCount;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getSignatureSize;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getTotalFeeforRequest;

import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.fee.FeeObject;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FeeSchedule;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FeeCalculator {
    private static final Logger log = LogManager.getLogger(FeeCalculator.class);

    private final HapiSpecSetup setup;
    private final Map<HederaFunctionality, Map<SubType, FeeData>> opFeeData = new HashMap<>();
    private final FeesAndRatesProvider provider;

    private long fixedFee = Long.MIN_VALUE;
    private boolean usingFixedFee = false;

    private int tokenTransferUsageMultiplier = 1;

    public FeeCalculator(final HapiSpecSetup setup, final FeesAndRatesProvider provider) {
        this.setup = setup;
        this.provider = provider;
    }

    public Map<HederaFunctionality, Map<SubType, FeeData>> getCurrentOpFeeData() {
        return opFeeData;
    }

    public void init() {
        if (setup.useFixedFee()) {
            usingFixedFee = true;
            fixedFee = setup.fixedFee();
            return;
        }
        final FeeSchedule feeSchedule = provider.currentSchedule();
        feeSchedule
                .getTransactionFeeScheduleList()
                .forEach(f -> opFeeData.put(f.getHederaFunctionality(), feesListToMap(f.getFeesList())));
        tokenTransferUsageMultiplier = setup.feesTokenTransferUsageMultiplier();
    }

    public static Map<SubType, FeeData> feesListToMap(final List<FeeData> feesList) {
        final Map<SubType, FeeData> feeDataMap = new HashMap<>();
        for (final FeeData feeData : feesList) {
            feeDataMap.put(feeData.getSubType(), feeData);
        }
        return feeDataMap;
    }

    private long maxFeeTinyBars(final SubType subType) {
        return usingFixedFee
                ? fixedFee
                : Arrays.stream(HederaFunctionality.values())
                        .mapToLong(op -> Optional.ofNullable(opFeeData.get(op))
                                .map(fd -> {
                                    final var pricesForSubtype = fd.get(subType);
                                    if (pricesForSubtype == null) {
                                        return 0L;
                                    } else {
                                        return pricesForSubtype.getServicedata().getMax()
                                                + pricesForSubtype.getNodedata().getMax()
                                                + pricesForSubtype
                                                        .getNetworkdata()
                                                        .getMax();
                                    }
                                })
                                .orElse(0L))
                        .max()
                        .orElse(0L);
    }

    public long maxFeeTinyBars() {
        return maxFeeTinyBars(SubType.DEFAULT);
    }

    public long forOp(final HederaFunctionality op, final FeeData knownActivity) {
        return forOp(op, SubType.DEFAULT, knownActivity);
    }

    @FunctionalInterface
    public interface ActivityMetrics {
        FeeData compute(TransactionBody body, SigValueObj sigUsage) throws Throwable;
    }

    public long forActivityBasedOp(
            final HederaFunctionality op,
            final ActivityMetrics metricsCalculator,
            final Transaction txn,
            final int numPayerSigs)
            throws Throwable {
        final FeeData activityMetrics = metricsFor(txn, numPayerSigs, metricsCalculator);
        final var subType = activityMetrics.getSubType();
        return forOp(op, subType, activityMetrics);
    }

    public long forActivityBasedOpWithDetails(
            final HederaFunctionality op,
            final ActivityMetrics metricsCalculator,
            final Transaction txn,
            final int numPayerSigs,
            final AtomicReference<FeeObject> obs)
            throws Throwable {
        final FeeData activityMetrics = metricsFor(txn, numPayerSigs, metricsCalculator);
        return forOpWithDetails(op, SubType.DEFAULT, activityMetrics, obs);
    }

    public long forActivityBasedOp(
            final HederaFunctionality op,
            final SubType subType,
            final ActivityMetrics metricsCalculator,
            final Transaction txn,
            final int numPayerSigs)
            throws Throwable {
        final FeeData activityMetrics = metricsFor(txn, numPayerSigs, metricsCalculator);
        return forOp(op, subType, activityMetrics);
    }

    private FeeData metricsFor(final Transaction txn, final int numPayerSigs, final ActivityMetrics metricsCalculator)
            throws Throwable {
        final SigValueObj sigUsage = sigUsageGiven(txn, numPayerSigs);
        final TransactionBody body = CommonUtils.extractTransactionBody(txn);
        return metricsCalculator.compute(body, sigUsage);
    }

    private long forOp(final HederaFunctionality op, final SubType subType, final FeeData knownActivity) {
        if (usingFixedFee) {
            return fixedFee;
        }
        try {
            final Map<SubType, FeeData> activityPrices = opFeeData.get(op);
            return getTotalFeeforRequest(activityPrices.get(subType), knownActivity, provider.rates());
        } catch (final Throwable t) {
            log.warn("Unable to calculate fee for op {} (subType={}), using max fee!", op, subType, t);
        }
        return maxFeeTinyBars(subType);
    }

    public long forOpWithDetails(
            final HederaFunctionality op,
            final SubType subType,
            final FeeData knownActivity,
            final AtomicReference<FeeObject> obs) {
        try {
            final var activityPrices = opFeeData.get(op).get(subType);
            final var fees = getFeeObject(activityPrices, knownActivity, provider.rates());
            obs.set(fees);
            return getTotalFeeforRequest(activityPrices, knownActivity, provider.rates());
        } catch (final Throwable t) {
            throw new IllegalArgumentException("Calculation not observable!", t);
        }
    }

    private SigValueObj sigUsageGiven(final Transaction txn, final int numPayerSigs) {
        final int size = getSignatureSize(txn);
        final int totalNumSigs = getSignatureCount(txn);
        return new SigValueObj(totalNumSigs, numPayerSigs, size);
    }

    public int tokenTransferUsageMultiplier() {
        return tokenTransferUsageMultiplier;
    }
}
