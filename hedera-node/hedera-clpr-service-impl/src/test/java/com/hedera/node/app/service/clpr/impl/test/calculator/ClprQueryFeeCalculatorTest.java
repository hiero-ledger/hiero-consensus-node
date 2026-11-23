// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.calculator;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_GET_LEDGER_CONFIGURATION;
import static com.hedera.node.app.service.clpr.impl.calculator.ClprFeeCalculator.VERIFIER_FEE_GAS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;

import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.clpr.impl.ClprServiceImpl;
import com.hedera.node.app.service.clpr.impl.calculator.ClprQueryFeeCalculator;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClprQueryFeeCalculatorTest {

    private static final long BASE_FEE_TINYCENTS = 100_000_000L;
    private static final long GAS_FEE_TINYCENTS = 852L;

    private final ClprQueryFeeCalculator subject = new ClprQueryFeeCalculator(
            CLPR_GET_LEDGER_CONFIGURATION, Query.QueryOneOfType.CLPR_GET_LEDGER_CONFIGURATION);

    @Test
    @DisplayName("getQueryType returns the discriminant supplied at construction")
    void returnsQueryType() {
        assertThat(subject.getQueryType()).isEqualTo(Query.QueryOneOfType.CLPR_GET_LEDGER_CONFIGURATION);
    }

    @Test
    @DisplayName("A query is charged its base fee plus verifier gas")
    void chargesBaseFeeAndVerifierGas() {
        final var feeResult = new FeeResult();
        final var feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.GAS, GAS_FEE_TINYCENTS))
                .services(makeService(
                        "ClprService",
                        makeServiceFee(
                                CLPR_GET_LEDGER_CONFIGURATION, BASE_FEE_TINYCENTS, makeExtraIncluded(Extra.GAS, 0))))
                .build();

        subject.accumulateNodePayment(Query.DEFAULT, null, feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents())
                .isEqualTo(BASE_FEE_TINYCENTS + VERIFIER_FEE_GAS * GAS_FEE_TINYCENTS);
    }

    @Test
    @DisplayName("A query not in the schedule is free")
    void freeWhenScheduleMissingEntry() {
        final var feeResult = new FeeResult();

        subject.accumulateNodePayment(Query.DEFAULT, null, feeResult, FeeSchedule.DEFAULT);

        assertThat(feeResult.getServiceTotalTinycents()).isZero();
    }

    @Test
    @DisplayName("The CLPR service prices both of its queries, which are paid")
    void serviceRegistersBothQueries() {
        assertThat(new ClprServiceImpl().queryFeeCalculators())
                .extracting(QueryFeeCalculator::getQueryType)
                .containsExactlyInAnyOrder(
                        Query.QueryOneOfType.CLPR_GET_LEDGER_CONFIGURATION,
                        Query.QueryOneOfType.CLPR_GET_ENDPOINT_MANIFEST);
    }
}
