// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.schemas;

import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_KEY;
import static com.hedera.node.app.history.schemas.V0730HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0730HistorySchemaTest {

    @Mock
    private MigrationContext ctx;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<ProtoBytes> singletonState;

    private final V0730HistorySchema subject = new V0730HistorySchema();

    @Test
    void definesExpectedSingleton() {
        final var statesToCreate = subject.statesToCreate();

        assertEquals(1, statesToCreate.size());
        final var def = statesToCreate.iterator().next();
        assertEquals(WRAPS_PROVING_KEY_HASH_KEY, def.stateKey());
        assertEquals(WRAPS_PROVING_KEY_HASH_STATE_ID, def.stateId());
        assertTrue(def.singleton());
    }

    @Test
    void restartPutsDefaultWhenNotGenesis() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<ProtoBytes>getSingleton(WRAPS_PROVING_KEY_HASH_STATE_ID))
                .willReturn(singletonState);

        subject.restart(ctx);

        verify(singletonState).put(ProtoBytes.DEFAULT);
    }

    @Test
    void restartDoesNothingOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        verifyNoInteractions(writableStates, singletonState);
    }
}
