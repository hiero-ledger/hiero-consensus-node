// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.fees;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getTotalFeeforRequest;

import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FeeSchedule;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SubType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FeeCalculator {
    private static final Logger log = LogManager.getLogger(FeeCalculator.class);

    private final HapiSpecSetup setup;
    private final Map<HederaFunctionality, Map<SubType, FeeData>> opFeeData = new HashMap<>();
    private final FeesAndRatesProvider provider;

    private long fixedFee = Long.MIN_VALUE;
    private boolean usingFixedFee = false;

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

    public long forOp(final HederaFunctionality op, final FeeData knownActivity) {
        return forOp(op, SubType.DEFAULT, knownActivity);
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
}
