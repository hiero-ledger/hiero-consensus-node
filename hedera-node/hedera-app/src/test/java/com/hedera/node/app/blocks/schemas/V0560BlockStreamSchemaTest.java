// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.schemas;

import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_KEY;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class V0560BlockStreamSchemaTest {

    @Mock
    private MigrationContext migrationContext;

    @Mock
    private WritableStates writableStates;

    @Mock
    private Consumer<Bytes> migratedBlockHashConsumer;

    @Mock
    private WritableSingletonState<BlockStreamInfo> state;

    private V0560BlockStreamSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V0560BlockStreamSchema(migratedBlockHashConsumer);
    }

    @Test
    void versionIsV0560() {
        assertEquals(new SemanticVersion(0, 56, 0, "", ""), subject.getVersion());
    }

    @Test
    void createsOneSingleton() {
        final var stateDefs = subject.statesToCreate(DEFAULT_CONFIG);
        assertEquals(1, stateDefs.size());
        final var def = stateDefs.iterator().next();
        assertTrue(def.singleton());
        assertEquals(BLOCK_STREAM_INFO_KEY, def.stateKey());
        assertEquals(BLOCK_STREAM_INFO_STATE_ID, def.stateId());
    }

    @Test
    void createsDefaultInfoAtGenesis() {
        given(migrationContext.newStates()).willReturn(writableStates);
        given(writableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .willReturn(state);
        given(migrationContext.isGenesis()).willReturn(true);

        subject.restart(migrationContext);

        verify(state).put(BlockStreamInfo.newBuilder().blockNumber(-1).build());
    }

    @Test
    void assumesMigrationIfNotGenesisAndStateIsNull() {
        final var blockInfo = new BlockInfo(
                666L,
                new Timestamp(1_234_567L, 0),
                Bytes.fromHex("abcd".repeat(24 * 256)),
                new Timestamp(1_234_567L, 890),
                false,
                new Timestamp(1_234_567L, 123),
                new Timestamp(1_234_567L, 123),
                new Timestamp(1_234_567L, 123));
        final var sharedValues = Map.<String, Object>of(
                "SHARED_BLOCK_RECORD_INFO",
                blockInfo,
                "SHARED_RUNNING_HASHES",
                new RunningHashes(
                        Bytes.fromHex("aa".repeat(48)),
                        Bytes.fromHex("bb".repeat(48)),
                        Bytes.fromHex("cc".repeat(48)),
                        Bytes.fromHex("dd".repeat(48))));
        given(migrationContext.newStates()).willReturn(writableStates);
        given(migrationContext.previousVersion()).willReturn(SemanticVersion.DEFAULT);
        given(writableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .willReturn(state);
        given(migrationContext.sharedValues()).willReturn(sharedValues);

        subject.restart(migrationContext);

        verify(migratedBlockHashConsumer).accept(Bytes.fromHex("abcd".repeat(24)));
        final var expectedInfo = new BlockStreamInfo(
                blockInfo.lastBlockNumber(),
                blockInfo.firstConsTimeOfLastBlock(),
                Bytes.fromHex("dd".repeat(48) + "cc".repeat(48) + "bb".repeat(48) + "aa".repeat(48)),
                Bytes.fromHex("abcd".repeat(24 * 255)),
                Bytes.EMPTY,
                Bytes.EMPTY,
                0,
                List.of(),
                blockInfo.consTimeOfLastHandledTxn(),
                false,
                SemanticVersion.DEFAULT,
                blockInfo.consTimeOfLastHandledTxn(),
                blockInfo.consTimeOfLastHandledTxn(),
                Bytes.EMPTY,
                Bytes.EMPTY,
                Bytes.EMPTY);
        verify(state).put(expectedInfo);
    }

    @Test
    void migrationIsNoopIfNotGenesisAndInfoIsNonNull() {
        given(migrationContext.newStates()).willReturn(writableStates);
        given(writableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .willReturn(state);
        given(state.get()).willReturn(BlockStreamInfo.DEFAULT);

        subject.restart(migrationContext);

        verifyNoInteractions(migratedBlockHashConsumer);
    }
}
