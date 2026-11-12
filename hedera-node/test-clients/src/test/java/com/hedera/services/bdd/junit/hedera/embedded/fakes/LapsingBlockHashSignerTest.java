// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.hints.impl.RsaContext;
import com.hedera.node.app.tss.DualBlockHashSigner;
import com.hedera.node.app.tss.TssSubmissions;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class LapsingBlockHashSignerTest {
    @Test
    void completedWitnessTraversesBothWrappersEvenWhenNewSigningIsPaused() {
        final var delegate = mock(BlockHashSigner.class);
        final var dual = new DualBlockHashSigner(
                mock(RsaContext.class), new ConcurrentHashMap<>(), mock(TssSubmissions.class), delegate);
        final var subject = new LapsingBlockHashSigner(dual);
        final var vk = Bytes.wrap("captured key");
        final var witness =
                HintsKeyWitness.newBuilder().targetHintsConstructionId(7).build();
        subject.startIgnoringRequests();

        subject.publishKeyWitness(vk, witness);

        verify(delegate).publishKeyWitness(vk, witness);
    }
}
