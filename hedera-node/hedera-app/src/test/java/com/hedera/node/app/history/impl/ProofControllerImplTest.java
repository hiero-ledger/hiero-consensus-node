/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.history.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.HistorySignature;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.ReadableHistoryStore.HistorySignaturePublication;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.roster.RosterTransitionWeights;
import com.hedera.node.app.tss.TssKeyPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProofControllerImplTest {
    private static final long SELF_ID = 42L;
    private static final Bytes METADATA = Bytes.wrap("M");
    private static final Bytes LEDGER_ID = Bytes.wrap("LID");
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final TssKeyPair PROOF_KEY_PAIR = new TssKeyPair(Bytes.EMPTY, Bytes.EMPTY);
    private static final ProofKeyPublication KEY_PUBLICATION = new ProofKeyPublication(1L, Bytes.EMPTY, CONSENSUS_NOW);
    private static final HistorySignaturePublication SIGNATURE_PUBLICATION =
            new HistorySignaturePublication(1L, HistorySignature.DEFAULT, CONSENSUS_NOW);

    @Mock
    private Executor executor;

    @Mock
    private HistoryLibrary library;

    @Mock
    private HistoryLibraryCodec codec;

    @Mock
    private HistorySubmissions submissions;

    @Mock
    private RosterTransitionWeights weights;

    @Mock
    private Consumer<HistoryProof> proofConsumer;

    @Mock
    private WritableHistoryStore store;

    private ProofControllerImpl subject;

    @BeforeEach
    void setUp() {
        subject = new ProofControllerImpl(
                SELF_ID,
                PROOF_KEY_PAIR,
                LEDGER_ID,
                HistoryProofConstruction.DEFAULT,
                weights,
                executor,
                library,
                codec,
                submissions,
                List.of(),
                List.of(),
                proofConsumer);
    }

    @Test
    void nothingSupportedYet() {
        assertThrows(UnsupportedOperationException.class, () -> subject.constructionId());
        assertThrows(UnsupportedOperationException.class, () -> subject.isStillInProgress());
        assertThrows(
                UnsupportedOperationException.class, () -> subject.advanceConstruction(CONSENSUS_NOW, METADATA, store));
        assertThrows(UnsupportedOperationException.class, () -> subject.addProofKeyPublication(KEY_PUBLICATION));
        assertThrows(UnsupportedOperationException.class, () -> subject.addSignaturePublication(SIGNATURE_PUBLICATION));
        assertThrows(
                UnsupportedOperationException.class, () -> subject.addProofVote(1L, HistoryProofVote.DEFAULT, store));
        assertThrows(UnsupportedOperationException.class, () -> subject.cancelPendingWork());
    }
}
