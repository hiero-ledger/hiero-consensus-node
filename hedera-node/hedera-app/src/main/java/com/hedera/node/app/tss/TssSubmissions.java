// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.hints.impl.RsaContext;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Signature;

/**
 * Submits TSS node transactions to the network. Reuses the generic submission machinery of
 * {@link ConsensusSubmissions} and supplies the {@link NetworkAdminConfig} retry envelope.
 */
public class TssSubmissions extends ConsensusSubmissions {
    private static final Logger log = LogManager.getLogger(TssSubmissions.class);

    private final BiConsumer<TransactionBody, String> onFailure =
            (body, reason) -> log.warn("Failed to submit {} ({})", body, reason);

    public TssSubmissions(@NonNull final Executor executor, @NonNull final AppContext appContext) {
        super(executor, appContext);
    }

    @Override
    protected RetryEnvelope retryEnvelope(@NonNull final Configuration config) {
        final var adminConfig = config.getConfigData(NetworkAdminConfig.class);
        return new RetryEnvelope(
                adminConfig.timesToTrySubmission(), adminConfig.distinctTxnIdsToTry(), adminConfig.retryDelay());
    }

    /**
     * Signs the given bytes with the node's platform RSA signing key.
     *
     * @param bytes the bytes to sign
     * @return the platform signature
     */
    protected Signature sign(@NonNull final byte[] bytes) {
        return appContext.gossip().sign(bytes);
    }

    /**
     * Attempts to submit an RSA signature using the node's platform signing key.
     *
     * @param message the message to sign
     * @return a future that completes when the signature has been submitted
     */
    public CompletableFuture<Void> submitRsaSignature(@NonNull final Bytes message) {
        return submitRsaSignature(message, onFailure);
    }

    /**
     * Attempts to submit an RSA signature using the node's platform signing key.
     *
     * @param message the message to sign
     * @param onFailure a consumer to call if the transaction fails to submit
     * @return a future that completes when the signature has been submitted
     */
    protected CompletableFuture<Void> submitRsaSignature(
            @NonNull final Bytes message, @NonNull final BiConsumer<TransactionBody, String> onFailure) {
        requireNonNull(message);
        requireNonNull(onFailure);
        return submitIfActive(
                b -> {
                    final var signature = sign(message.toByteArray()).getBytes();
                    b.hintsPartialSignature(new HintsPartialSignatureTransactionBody(
                            RsaContext.CONSTRUCTION_ID, message, requireNonNull(signature)));
                },
                onFailure);
    }
}
