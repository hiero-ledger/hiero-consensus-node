// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.token.api.FeeStreamBuilder;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.workflows.handle.stack.Savepoint;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeeAccumulatorTest {
    private static final Fees FEES = new Fees(1, 2, 3);

    @Mock
    private TokenServiceApi tokenApi;

    @Mock
    private FeeStreamBuilder recordBuilder;

    @Mock
    private SavepointStackImpl stack;

    @Mock
    private Savepoint savepoint;

    private FeeAccumulator subject;

    @BeforeEach
    void setUp() {
        subject = new FeeAccumulator(tokenApi, recordBuilder, stack);
    }

    @Test
    void automaticallyTracksNodeFeesToTopSavepointIfNodeAccountIsEligible() {
        final var captor = ArgumentCaptor.forClass(LongConsumer.class);

        final var nodeAccountId = AccountID.newBuilder().accountNum(101L).build();
        subject.chargeFees(AccountID.DEFAULT, nodeAccountId, FEES, null);

        verify(tokenApi)
                .chargeFees(
                        eq(AccountID.DEFAULT),
                        eq(nodeAccountId),
                        eq(FEES),
                        eq(recordBuilder),
                        eq(null),
                        captor.capture());

        final var onNodeFee = captor.getValue();
        given(stack.peek()).willReturn(savepoint);
        onNodeFee.accept(42L);

        verify(savepoint).trackCollectedNodeFee(42L);
    }
}
