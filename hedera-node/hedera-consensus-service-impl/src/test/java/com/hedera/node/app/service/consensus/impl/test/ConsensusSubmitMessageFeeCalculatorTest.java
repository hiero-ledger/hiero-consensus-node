// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.SimpleFeeContextImpl;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.calculator.ConsensusSubmitMessageFeeCalculator;
import com.hedera.node.app.service.consensus.impl.test.handlers.ConsensusTestBase;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
 * Unit tests for {@link ConsensusSubmitMessageFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
public class ConsensusSubmitMessageFeeCalculatorTest extends ConsensusTestBase {
    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;
    private FeeSchedule testSchedule;

    @BeforeEach
    void setUp() {
        testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(testSchedule, Set.of(new ConsensusSubmitMessageFeeCalculator()));
        lenient().when(feeContext.functionality()).thenReturn(HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE);
    }

    @Nested
    @DisplayName("submit message")
    class SubmitMessageTests {
        @Test
        @DisplayName("submit message to normal topic")
        void submitMessage() {
            givenValidTopic();

            // submit message to topic
            final var op = ConsensusSubmitMessageTransactionBody.newBuilder()
                    .topicID(topicId)
                    .message(Bytes.wrap("foo"))
                    .build();
            final var body =
                    TransactionBody.newBuilder().consensusSubmitMessage(op).build();

            final var feeCtx = mock(FeeContext.class);
            lenient().when(feeCtx.functionality()).thenReturn(HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE);
            ReadableTopicStore readableStore = mock(ReadableTopicStore.class);
            given(storeFactory.readableStore(ReadableTopicStore.class)).willReturn(readableStore);
            given(feeCtx.readableStore(ReadableTopicStore.class)).willReturn(readableStore);
            given(readableStore.getTopic(topicId))
                    .willReturn(Topic.newBuilder()
                            .runningHash(Bytes.wrap(new byte[48]))
                            .sequenceNumber(1L)
                            .build());

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeCtx, null));
            assertThat(result).isNotNull();
            Assertions.assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            Assertions.assertThat(result.getServiceTotalTinycents()).isEqualTo(498500000L);
            Assertions.assertThat(result.getNetworkTotalTinycents()).isEqualTo(200000L);
        }

        @Test
        @DisplayName("submit message to custom fees topic")
        void submitMessageWithCustomFees() {
            givenValidTopic();

            final var op = ConsensusSubmitMessageTransactionBody.newBuilder()
                    .topicID(topic.topicId())
                    .message(Bytes.wrap("foo"))
                    .build();
            final var body =
                    TransactionBody.newBuilder().consensusSubmitMessage(op).build();

            final var feeCtx = mock(FeeContext.class);
            lenient().when(feeCtx.functionality()).thenReturn(HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE);
            ReadableTopicStore readableStore = mock(ReadableTopicStore.class);
            given(storeFactory.readableStore(ReadableTopicStore.class)).willReturn(readableStore);
            given(feeCtx.readableStore(ReadableTopicStore.class)).willReturn(readableStore);
            // the 'topic' variable already has custom fees
            given(readableStore.getTopic(topic.topicId())).willReturn(topic);

            final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeCtx, null));
            assertThat(result).isNotNull();
            Assertions.assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
            Assertions.assertThat(result.getServiceTotalTinycents()).isEqualTo(498500000L + 500000000L);
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
                        makeExtraDef(Extra.STATE_BYTES, 110L),
                        makeExtraDef(Extra.CONSENSUS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE, 500000000))
                .services(makeService(
                        "Consensus",
                        makeServiceFee(
                                HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE,
                                498500000L,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.CONSENSUS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE, 0))))
                .build();
    }
}
