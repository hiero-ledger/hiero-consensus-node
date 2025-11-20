// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.consensus.ConsensusGetTopicInfoQuery;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.consensus.impl.calculator.ConsensusGetTopicInfoFeeCalculator;
import com.hedera.node.app.spi.fees.CalculatorState;
import com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ConsensusGetTopicInfoFeeCalculatorTest {
    static final long GET_INFO_BASE_FEE = 55L;
    private SimpleFeeCalculatorImpl feeCalculator;

    @Mock
    private CalculatorState calculatorState;

    @BeforeEach
    void setUp() {
        FeeSchedule testSchedule = createTestFeeSchedule();
        feeCalculator =
                new SimpleFeeCalculatorImpl(testSchedule, Set.of(), Set.of(new ConsensusGetTopicInfoFeeCalculator()));
    }

    @Test
    @DisplayName("calculate fee")
    void calculateFee() {
        final var op = ConsensusGetTopicInfoQuery.newBuilder().build();
        final var query = Query.newBuilder().consensusGetTopicInfo(op).build();
        final var result = feeCalculator.calculateQueryFee(query, calculatorState);

        assertThat(result).isNotNull();
        Assertions.assertThat(result.node).isEqualTo(100000L);
        Assertions.assertThat(result.service).isEqualTo(GET_INFO_BASE_FEE);
        Assertions.assertThat(result.network).isEqualTo(200000L);
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 10)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000L),
                        makeExtraDef(Extra.KEYS, 100000000L),
                        makeExtraDef(Extra.BYTES, 110L))
                .services(makeService(
                        "Consensus",
                        makeServiceFee(
                                HederaFunctionality.CONSENSUS_CREATE_TOPIC,
                                498500000L,
                                makeExtraIncluded(Extra.SIGNATURES, 1)),
                        makeServiceFee(HederaFunctionality.CONSENSUS_GET_TOPIC_INFO, GET_INFO_BASE_FEE)))
                .build();
    }
}
