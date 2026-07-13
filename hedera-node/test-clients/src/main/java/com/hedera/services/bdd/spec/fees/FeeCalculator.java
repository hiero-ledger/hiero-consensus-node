// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.fees;

import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FeeSchedule;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SubType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FeeCalculator {
    private final HapiSpecSetup setup;
    private final Map<HederaFunctionality, Map<SubType, FeeData>> opFeeData = new HashMap<>();
    private final FeesAndRatesProvider provider;

    public FeeCalculator(final HapiSpecSetup setup, final FeesAndRatesProvider provider) {
        this.setup = setup;
        this.provider = provider;
    }

    public Map<HederaFunctionality, Map<SubType, FeeData>> getCurrentOpFeeData() {
        return opFeeData;
    }

    public void init() {
        if (setup.useFixedFee()) {
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
}
