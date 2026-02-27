// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.NodePayment;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.node.app.service.token.impl.ReadableNodePaymentsStoreImpl;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableNodePaymentsStoreImplTest {

    @Mock
    private ReadableStates states;

    @Mock
    private ReadableSingletonState nodePaymentsState;

    private ReadableNodePaymentsStoreImpl subject;

    @BeforeEach
    void setUp() {
        given(states.getSingleton(NODE_PAYMENTS_STATE_ID)).willReturn(nodePaymentsState);
        subject = new ReadableNodePaymentsStoreImpl(states);
    }

    @Test
    void testGetReturnsNodePayments() {
        final var nodePayments = NodePayments.newBuilder()
                .payments(List.of(NodePayment.newBuilder()
                        .nodeAccountId(AccountID.newBuilder().accountNum(3).build())
                        .fees(100L)
                        .build()))
                .lastNodeFeeDistributionTime(
                        Timestamp.newBuilder().seconds(1000L).build())
                .build();
        given(nodePaymentsState.get()).willReturn(nodePayments);

        final var result = subject.get();

        assertNotNull(result);
        assertEquals(1, result.payments().size());
        assertEquals(100L, result.payments().getFirst().fees());
        assertEquals(3L, result.payments().getFirst().nodeAccountId().accountNum());
        assertEquals(1000L, result.lastNodeFeeDistributionTime().seconds());
    }

    @Test
    void testGetReturnsDefaultNodePayments() {
        given(nodePaymentsState.get()).willReturn(NodePayments.DEFAULT);
        final var result = subject.get();
        assertNotNull(result);
        assertEquals(0, result.payments().size());
    }

    @Test
    void testGetWithMultiplePayments() {
        final var nodePayments = NodePayments.newBuilder()
                .payments(List.of(
                        NodePayment.newBuilder()
                                .nodeAccountId(
                                        AccountID.newBuilder().accountNum(3).build())
                                .fees(100L)
                                .build(),
                        NodePayment.newBuilder()
                                .nodeAccountId(
                                        AccountID.newBuilder().accountNum(4).build())
                                .fees(200L)
                                .build(),
                        NodePayment.newBuilder()
                                .nodeAccountId(
                                        AccountID.newBuilder().accountNum(5).build())
                                .fees(300L)
                                .build()))
                .build();
        given(nodePaymentsState.get()).willReturn(nodePayments);

        final var result = subject.get();

        assertNotNull(result);
        assertEquals(3, result.payments().size());
        assertEquals(100L, result.payments().get(0).fees());
        assertEquals(200L, result.payments().get(1).fees());
        assertEquals(300L, result.payments().get(2).fees());
    }

    @Test
    void testConstructorWithNullStatesThrows() {
        assertThrows(NullPointerException.class, () -> new ReadableNodePaymentsStoreImpl(null));
    }

    @Test
    void testGetThrowsWhenStateReturnsNull() {
        given(nodePaymentsState.get()).willReturn(null);
        assertThrows(NullPointerException.class, () -> subject.get());
    }
}
