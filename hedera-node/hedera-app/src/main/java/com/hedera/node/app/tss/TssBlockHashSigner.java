// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
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
 *     <li><b>If both hinTS and history proofs are enabled:</b>
 *     <ul>
 *         <li>Is not ready to sign during bootstrap phase until the genesis hinTS construction has completed
 *         preprocessing and reached a consensus verification key; and until the history service has collected
 *         a sufficient set of Schnorr signatures on the genesis TSS address book hash concatenated with the
 *         genesis hinTS verification key to serve as witnesses for the genesis SNARK.</li>
 *         <li>To sign, initiates async aggregation of partial hinTS signatures from the active construction,
 *         packaging this async delivery into a full TSS signature with chain-of-trust proof of the hinTS
 *         verification key used for signing.</li>
 *     </ul>
 *     </li>
 * </ol>
 * Note that it doesn't make sense to enable history proofs without hinTS, because there are no verification keys
 * to prove chain of trust over.
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
        final var streamMode = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamMode();
        this.hintsService = (tssConfig.hintsEnabled() && streamMode != RECORDS) ? hintsService : null;
        this.historyService = (tssConfig.historyEnabled() && streamMode != RECORDS) ? historyService : null;
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
    public Attempt sign(@NonNull final Bytes blockHash) {
        requireNonNull(blockHash);
        if (!isReady()) {
            throw new IllegalStateException("TSS protocol not ready to sign block hash " + blockHash);
        }
        if (hintsService == null) {
            return new Attempt(null, null, CompletableFuture.supplyAsync(() -> noThrowSha384HashOf(blockHash)));
        } else {
            final var signing = hintsService.sign(blockHash);
            if (historyService == null) {
                return new Attempt(signing.verificationKey(), null, signing.future());
            } else {
                final var chainOfTrustProof = historyService.getCurrentChainOfTrustProof(signing.verificationKey());
                return new Attempt(signing.verificationKey(), chainOfTrustProof, signing.future());
            }
        }
    }
}
