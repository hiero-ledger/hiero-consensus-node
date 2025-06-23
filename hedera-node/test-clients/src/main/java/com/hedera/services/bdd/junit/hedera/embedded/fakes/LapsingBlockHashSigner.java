// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.tss.TssBlockHashSigner;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link BlockHashSigner} that can start ignoring requests for ledger signatures when requested.
 */
public class LapsingBlockHashSigner implements BlockHashSigner {
    private final BlockHashSigner delegate;

    private boolean ignoreRequests = false;

    public LapsingBlockHashSigner(
            @NonNull final HintsService hintsService,
            @NonNull final HistoryService historyService,
            @NonNull final ConfigProvider configProvider) {
        requireNonNull(hintsService);
        requireNonNull(historyService);
        requireNonNull(configProvider);
        this.delegate = new TssBlockHashSigner(hintsService, historyService, configProvider);
    }

    /**
     * When called, will start ignoring any requests for ledger signatures.
     */
    public void startIgnoringRequests() {
        ignoreRequests = true;
    }

    /**
     * When called, will stop ignoring any requests for ledger signatures.
     */
    public void stopIgnoringRequests() {
        ignoreRequests = false;
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public long schemeId() {
        return delegate.schemeId();
    }

    @Override
    public Bytes verificationKey() {
        return delegate.verificationKey();
    }

    @Override
    public CompletableFuture<Bytes> signFuture(@NonNull final Bytes blockHash) {
        return ignoreRequests ? new CompletableFuture<>() : delegate.signFuture(blockHash);
    }
}
