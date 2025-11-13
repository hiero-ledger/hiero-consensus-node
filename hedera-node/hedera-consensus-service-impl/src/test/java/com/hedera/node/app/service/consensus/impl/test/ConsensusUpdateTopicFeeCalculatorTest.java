// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.consensus.ConsensusUpdateTopicTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.consensus.impl.calculator.ConsensusUpdateTopicFeeCalculator;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ConsensusUpdateTopicFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
public class ConsensusUpdateTopicFeeCalculatorTest {
    @Mock
    private CalculatorState calculatorState;

    private SimpleFeeCalculatorImpl feeCalculator;
    private FeeSchedule testSchedule;

    @BeforeEach
    void setUp() {
        testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(testSchedule, Set.of(new ConsensusUpdateTopicFeeCalculator()));
    }

    @Nested
    @DisplayName("update topic")
    class UpdateTopicTests {
        @Test
        @DisplayName("update topic")
        void updateTopic() {
            final var op = ConsensusUpdateTopicTransactionBody.newBuilder().build();
            final var body =
                    TransactionBody.newBuilder().consensusUpdateTopic(op).build();
            final var result = feeCalculator.calculateTxFee(body, calculatorState);
            assertThat(result).isNotNull();
            Assertions.assertThat(result.node).isEqualTo(100000L);
            Assertions.assertThat(result.service).isEqualTo(498500000L);
            Assertions.assertThat(result.network).isEqualTo(200000L);
        }
    }

    // Helper method to create test fee schedule using real production values from simpleFeesSchedules.json
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
                                HederaFunctionality.CONSENSUS_UPDATE_TOPIC,
                                498500000L,
                                makeExtraIncluded(Extra.SIGNATURES, 1))))
                .build();
    }
}
