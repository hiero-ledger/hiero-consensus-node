// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.node.app.hapi.utils.blocks.StateChangeDeltas;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.junit.jupiter.api.Test;

class ExpandedStateChangeDeltaEmissionTest {
    @Test
    void formerlyUnlistedNodeAndBlockInfoUseTheUniversalRule() {
        final var prior = Node.newBuilder()
                .nodeId(1)
                .description("description".repeat(30))
                .build();
        final var next = prior.copyBuilder()
                .accountId(AccountID.newBuilder().accountNum(5))
                .build();
        final var maps = new ImmediateStateChangeListener();
        maps.beginBlock(true);
        maps.mapUpdateChange(1, new com.hedera.hapi.node.state.common.EntityNumber(1), prior, next);
        final var update = maps.encodeForStream(BlockItem.newBuilder()
                        .stateChanges(StateChanges.newBuilder().stateChanges(maps.getKvStateChanges()))
                        .build())
                .stateChangesOrThrow()
                .stateChanges()
                .getFirst()
                .mapUpdateOrThrow();
        assertThat(update.partial()).isTrue();
        assertThat(StateChangeDeltas.applyMap(1, prior, update)).isEqualTo(next);
        final var singletons = new BoundaryStateChangeListener(
                new StoreMetricsServiceImpl(new NoOpMetrics()), HederaTestConfigBuilder::createConfig);
        singletons.beginBlock(true);
        final var oldInfo =
                BlockInfo.newBuilder().blockHashes(Bytes.wrap("h".repeat(200))).build();
        final var newInfo = oldInfo.copyBuilder().lastBlockNumber(5).build();
        singletons.singletonUpdateChange(3, oldInfo, newInfo);
        final var singleton = singletons.allStateChanges().getFirst().singletonUpdateOrThrow();
        assertThat(singleton.partial()).isTrue();
        assertThat(StateChangeDeltas.applySingleton(3, oldInfo, singleton)).isEqualTo(newInfo);
    }

    @Test
    void fileAppendIsAWholeFieldAndThresholdCanSelectFullOutput() {
        final var id = FileID.newBuilder().fileNum(123).build();
        final var prior = File.newBuilder()
                .fileId(id)
                .memo("m".repeat(100))
                .contents(Bytes.wrap("a".repeat(1024)))
                .build();
        final var next = prior.copyBuilder()
                .contents(Bytes.wrap("a".repeat(1024) + "suffix"))
                .build();
        for (int threshold : new int[] {64, 4096}) {
            final var listener = new ImmediateStateChangeListener();
            listener.beginBlock(true, threshold);
            listener.mapUpdateChange(6, id, prior, next);
            final var update = listener.encodeForStream(BlockItem.newBuilder()
                            .stateChanges(StateChanges.newBuilder().stateChanges(listener.getKvStateChanges()))
                            .build())
                    .stateChangesOrThrow()
                    .stateChanges()
                    .getFirst()
                    .mapUpdateOrThrow();
            assertThat(update.partial()).isEqualTo(threshold == 64);
            assertThat(update.valueOrThrow().fileValueOrThrow().contents()).isEqualTo(next.contents());
            assertThat(StateChangeDeltas.applyMap(6, prior, update)).isEqualTo(next);
        }
    }

    @Test
    void primitiveWrappersNormalizeStateAndWireRepresentationsDuringReplay() {
        final var value = new ProtoString("s".repeat(100));
        final var listener = new ImmediateStateChangeListener();
        listener.beginBlock(true);
        listener.mapUpdateChange(9999, AccountID.DEFAULT, value, value);
        final var update = listener.encodeForStream(BlockItem.newBuilder()
                        .stateChanges(StateChanges.newBuilder().stateChanges(listener.getKvStateChanges()))
                        .build())
                .stateChangesOrThrow()
                .stateChanges()
                .getFirst()
                .mapUpdateOrThrow();
        assertThat(update.partial()).isTrue();
        assertThat(StateChangeDeltas.applyMap(9999, value, update)).isEqualTo(value);
    }
}
