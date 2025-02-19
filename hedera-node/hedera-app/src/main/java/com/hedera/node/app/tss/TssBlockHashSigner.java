/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.tss;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link BlockHashSigner} that uses whatever parts of the TSS protocol are enabled to sign blocks.
 * That is,
 * <ol>
 *     <li><b>If neither hinTS nor history proofs are enabled:</b>
 *     <ul>
 *         <li>Is always ready to sign.</li>
 *         <li>To sign, schedules async delivery of the SHA-384 hash of the block hash as its "signature".</li>
 *     </ul>
 *     <li><b>If only hinTS is enabled:</b>
 *     <ul>
 *         <li>Is not ready to sign during bootstrap phase until the genesis hinTS construction has completed
 *         preprocessing and reached a consensus verification key.</li>
 *         <li>To sign, initiates async aggregation of partial hinTS signatures from the active construction.</li>
 *     </ul>
 *     </li>
 *     <li><b>If only history proofs are enabled:</b>
 *     <ul>
 *         <li>Is not ready to sign during bootstrap phase until the history service has collated as many
 *         Schnorr keys as it reasonably can for the genesis TSS address book; and accumulated signatures
 *         from a strong minority of those keys on the genesis TSS address book hash with empty metadata to
 *         derive a consensus genesis proof.</li>
 *         <li>To sign, schedules async delivery of the SHA-384 hash of the block hash as its "signature"
 *         but assembles a full TSS signature with proof of empty metadata in the TSS address book whose
 *         roster would have performed the hinTS signing.</li>
 *     </ul>
 *     </li>
 *     <li><b>If both hinTS and history proofs are enabled:</b>
 *     <ul>
 *         <li>Is not ready to sign during bootstrap phase until the genesis hinTS construction has completed
 *         preprocessing and reached a consensus verification key; and until the history service has collated
 *         as many Schnorr keys as it reasonably can for the genesis TSS address book; and accumulated signatures
 *         from a strong minority of those keys on the genesis TSS address book hash with the hinTS verification
 *         key as its metadata to derive a consensus genesis proof.</li>
 *         <li>To sign, initiates async aggregation of partial hinTS signatures from the active construction,
 *         packaging this async delivery into a full TSS signature with proof of the hinTS verification key as
 *         the metadata of the active TSS address book.</li>
 *     </ul>
 *     </li>
 * </ol>
 */
public class TssBlockHashSigner implements BlockHashSigner {
    @Nullable
    private final HintsService hintsService;

    @Nullable
    private final HistoryService historyService;

    public TssBlockHashSigner(
            @NonNull final HintsService hintsService,
            @NonNull final HistoryService historyService,
            @NonNull final ConfigProvider configProvider) {
        final var tssConfig = configProvider.getConfiguration().getConfigData(TssConfig.class);
        this.hintsService = tssConfig.hintsEnabled() ? hintsService : null;
        this.historyService = tssConfig.historyEnabled() ? historyService : null;
    }

    @Override
    public boolean isReady() {
        return (hintsService == null || hintsService.isReady()) && (historyService == null || historyService.isReady());
    }

    @Override
    public CompletableFuture<Bytes> signFuture(@NonNull final Bytes blockHash) {
        requireNonNull(blockHash);
        if (!isReady()) {
            throw new IllegalStateException("TSS protocol not ready to sign block hash " + blockHash);
        }
        if (historyService == null) {
            if (hintsService == null) {
                return CompletableFuture.supplyAsync(() -> noThrowSha384HashOf(blockHash));
            } else {
                return hintsService.signFuture(blockHash);
            }
        } else {
            final var vk = hintsService == null ? Bytes.EMPTY : hintsService.activeVerificationKeyOrThrow();
            final var proof = historyService.getCurrentProof(vk);
            if (hintsService == null) {
                return CompletableFuture.supplyAsync(() -> assemble(noThrowSha384HashOf(blockHash), vk, proof));
            } else {
                return hintsService.signFuture(blockHash).thenApply(signature -> assemble(signature, vk, proof));
            }
        }
    }

    /**
     * Assembles the ledger signature from the given components.
     *
     * @param signature the hinTS aggregate signature
     * @param vk the hinTS verification key
     * @param proof the recursive proof that the verification key was honestly assigned to the current roster
     * @return the assembled signature
     */
    static Bytes assemble(@NonNull final Bytes signature, @NonNull final Bytes vk, @NonNull final Bytes proof) {
        final var buffer = ByteBuffer.wrap(new byte[(int) (signature.length() + vk.length() + proof.length())]);
        signature.writeTo(buffer);
        vk.writeTo(buffer);
        proof.writeTo(buffer);
        return Bytes.wrap(buffer.array());
    }
}
