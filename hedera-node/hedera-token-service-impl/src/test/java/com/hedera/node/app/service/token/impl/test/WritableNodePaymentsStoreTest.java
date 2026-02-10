// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.NodePayment;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.node.app.service.token.impl.WritableNodePaymentsStore;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableNodePaymentsStoreTest {

    @Mock
    private WritableStates states;

    @Mock
    private WritableSingletonState nodePaymentsState;

    private WritableNodePaymentsStore subject;

    @BeforeEach
    void setUp() {
        given(states.getSingleton(NODE_PAYMENTS_STATE_ID)).willReturn(nodePaymentsState);
        subject = new WritableNodePaymentsStore(states);
    }

    @Test
    void testPutPersistsNodePayments() {
        final var nodePayments = NodePayments.newBuilder()
                .payments(List.of(NodePayment.newBuilder()
                        .nodeAccountId(AccountID.newBuilder().accountNum(3).build())
                        .fees(100L)
                        .build()))
                .lastNodeFeeDistributionTime(
                        Timestamp.newBuilder().seconds(1000L).build())
                .build();

        subject.put(nodePayments);

        verify(nodePaymentsState).put(nodePayments);
    }

    @Test
    void testPutWithNullThrowsNPE() {
        assertThrows(NullPointerException.class, () -> subject.put(null));
    }

    @Test
    void testResetForNewStakingPeriod() {
        final var lastDistributionTime = Timestamp.newBuilder().seconds(2000L).build();

        subject.resetForNewStakingPeriod(lastDistributionTime);

        final var captor = ArgumentCaptor.forClass(NodePayments.class);
        verify(nodePaymentsState).put(captor.capture());

        final var captured = captor.getValue();
        assertNotNull(captured);
        assertEquals(0, captured.payments().size());
        assertEquals(2000L, captured.lastNodeFeeDistributionTime().seconds());
    }

    @Test
    void testResetForNewStakingPeriodWithNullTimestamp() {
        subject.resetForNewStakingPeriod(null);

        final var captor = ArgumentCaptor.forClass(NodePayments.class);
        verify(nodePaymentsState).put(captor.capture());

        final var captured = captor.getValue();
        assertNotNull(captured);
        assertEquals(0, captured.payments().size());
    }

    @Test
    void testConstructorWithNullStatesThrows() {
        assertThrows(NullPointerException.class, () -> new WritableNodePaymentsStore(null));
    }

    @Test
    void testGetInheritedFromReadable() {
        final var nodePayments = NodePayments.newBuilder()
                .payments(List.of(NodePayment.newBuilder()
                        .nodeAccountId(AccountID.newBuilder().accountNum(5).build())
                        .fees(500L)
                        .build()))
                .build();
        given(nodePaymentsState.get()).willReturn(nodePayments);

        final var result = subject.get();

        assertNotNull(result);
        assertEquals(1, result.payments().size());
        assertEquals(500L, result.payments().getFirst().fees());
    }

    @Test
    void testPutMultiplePayments() {
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
                                .build()))
                .build();
        subject.put(nodePayments);
        verify(nodePaymentsState).put(nodePayments);
    }
}
