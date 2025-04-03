// SPDX-License-Identifier: Apache-2.0
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
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private static final Logger log = LogManager.getLogger(TssBlockHashSigner.class);

    public static final String SIGNER_READY_MSG = "TSS protocol ready to sign blocks";

    @Nullable
    private final HintsService hintsService;

    @Nullable
    private final HistoryService historyService;

    private boolean loggedReady = false;

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
        final boolean answer = (hintsService == null || hintsService.isReady())
                && (historyService == null || historyService.isReady());
        if (answer && !loggedReady) {
            log.info(SIGNER_READY_MSG);
            loggedReady = true;
        }
        return answer;
    }

    @Override
    public CompletableFuture<Bytes> signFuture(@NonNull final Bytes blockHash) {
        requireNonNull(blockHash);
        if (!isReady()) {
            throw new IllegalStateException("TSS protocol not ready to sign block hash " + blockHash);
        }
        final CompletableFuture<Bytes> result;

        if (historyService == null) {
            if (hintsService == null) {
                result = CompletableFuture.supplyAsync(() -> noThrowSha384HashOf(blockHash));
            } else {
                result = hintsService.signFuture(blockHash);
            }
        } else {
            if (hintsService == null) {
                result = CompletableFuture.supplyAsync(() -> noThrowSha384HashOf(blockHash));
            } else {
                result = hintsService.signFuture(blockHash);
            }
        }
        return result;
    }

    @Override
    public long activeSchemeId() {
        return (hintsService == null) ? 1 : hintsService.activeSchemeId();
    }

    @Override
    public Bytes activeVerificationKey() {
        return (hintsService == null) ? Bytes.EMPTY : hintsService.activeVerificationKeyOrThrow();
    }
}
