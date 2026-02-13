// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.consensus.ConsensusUpdateTopicTransactionBody;
import com.hedera.hapi.node.transaction.FeeExemptKeyList;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.service.consensus.impl.calculator.ConsensusUpdateTopicFeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeContextUtil;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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
    private FeeContext feeContext;

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
        static final Function<String, Key.Builder> KEY_BUILDER =
                value -> Key.newBuilder().ed25519(Bytes.wrap(value.getBytes()));

        @Test
        @DisplayName("update topic")
        void updateTopic() {
            final var op = ConsensusUpdateTopicTransactionBody.newBuilder().build();
            final var body =
                    TransactionBody.newBuilder().consensusUpdateTopic(op).build();
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));
            assertThat(result).isNotNull();
            Assertions.assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            Assertions.assertThat(result.getServiceTotalTinycents()).isEqualTo(498500000L);
            Assertions.assertThat(result.getNetworkTotalTinycents()).isEqualTo(200000L);
        }

        @Test
        @DisplayName("update topic with submit, admin, and fee schedule keys")
        void updateTopicWithKeys() {
            final String SCHEDULE_KEY = "scheduleKey";
            final String ADMIN_KEY = "adminKey";
            final Key SHEDULE_KEY = Key.newBuilder()
                    .keyList(KeyList.newBuilder()
                            .keys(KEY_BUILDER.apply(SCHEDULE_KEY).build()))
                    .build();
            // 1 key =
            final var op = ConsensusUpdateTopicTransactionBody.newBuilder()
                    .feeScheduleKey(SHEDULE_KEY)
                    .submitKey(SHEDULE_KEY)
                    .adminKey(KEY_BUILDER.apply(ADMIN_KEY).build())
                    .build();
            final var body =
                    TransactionBody.newBuilder().consensusUpdateTopic(op).build();
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));
            assertThat(result).isNotNull();
            Assertions.assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            // update topic base 498500000L
            // -1 keys =  100000000L
            Assertions.assertThat(result.getServiceTotalTinycents()).isEqualTo(498500000L + 100000000L * 2);
            Assertions.assertThat(result.getNetworkTotalTinycents()).isEqualTo(200000L);
        }

        @Test
        @DisplayName("update topic fee exempt key list")
        void updateTopicWithFeeExemptKeyList() {
            final String KEY1 = "key1";
            final String KEY2 = "key2";
            final String KEY3 = "key3";
            final String KEY4 = "key4";
            final var op = ConsensusUpdateTopicTransactionBody.newBuilder()
                    .feeExemptKeyList(FeeExemptKeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(KEY1).build(),
                                    KEY_BUILDER.apply(KEY2).build(),
                                    KEY_BUILDER.apply(KEY3).build(),
                                    KEY_BUILDER.apply(KEY4).build())
                            .build())
                    .build();
            final var body =
                    TransactionBody.newBuilder().consensusUpdateTopic(op).build();
            final var result = feeCalculator.calculateTxFee(body, SimpleFeeContextUtil.fromFeeContext(feeContext));
            assertThat(result).isNotNull();
            Assertions.assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            // update topic base 498500000L
            // 4-1 keys =  100000000L*3
            Assertions.assertThat(result.getServiceTotalTinycents()).isEqualTo(498500000L + 100000000L * 3);
            Assertions.assertThat(result.getNetworkTotalTinycents()).isEqualTo(200000L);
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
                                makeExtraIncluded(Extra.KEYS, 1))))
                .build();
    }
}
