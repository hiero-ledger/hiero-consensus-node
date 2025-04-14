// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats.signing;

import static com.swirlds.demo.stats.signing.StatsSigningTestingToolMain.SYSTEM_TRANSACTION_MARKER;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static org.hiero.base.utility.CommonUtils.hex;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyProvider;
import com.swirlds.common.crypto.TransactionSignature;
import com.swirlds.common.crypto.VerificationStatus;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;

public class StatsSigningTestingToolConsensusStateEventHandler
        implements ConsensusStateEventHandler<StatsSigningTestingToolState> {

    /**
     * use this for all logging, as controlled by the optional data/log4j2.xml file
     */
    private static final Logger logger = LogManager.getLogger(StatsSigningTestingToolConsensusStateEventHandler.class);

    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    /** if true, artificially take {@link #HANDLE_MICROS} to handle each consensus transaction */
    private static final boolean SYNTHETIC_HANDLE_TIME = false;

    /** the number of microseconds to wait before returning from the handle method */
    private static final int HANDLE_MICROS = 100;

    private final Supplier<SttTransactionPool> transactionPoolSupplier;

    public StatsSigningTestingToolConsensusStateEventHandler(Supplier<SttTransactionPool> transactionPoolSupplier) {
        this.transactionPoolSupplier = transactionPoolSupplier;
    }

    @Override
    public void onStateInitialized(
            @NonNull final StatsSigningTestingToolState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {}

    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull StatsSigningTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {

        final SttTransactionPool sttTransactionPool = transactionPoolSupplier.get();
        if (sttTransactionPool != null) {
            event.forEachTransaction(transaction -> {
                // We should consume in the callback the new form of system transactions in Bytes
                if (areTransactionBytesSystemOnes(transaction)) {
                    consumeSystemTransaction(transaction, event, stateSignatureTransactionCallback);
                    return;
                }

                final TransactionSignature transactionSignature =
                        sttTransactionPool.expandSignatures(transaction.getApplicationTransaction());
                if (transactionSignature != null) {
                    transaction.setMetadata(transactionSignature);
                    CRYPTOGRAPHY.verifySync(List.of(transactionSignature));
                }
            });
        }
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull StatsSigningTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        state.throwIfImmutable();

        round.forEachEventTransaction((event, transaction) -> {
            // We should consume in the callback the new form of system transactions in Bytes
            if (areTransactionBytesSystemOnes(transaction)) {
                consumeSystemTransaction(transaction, event, stateSignatureTransactionCallback);
            } else {
                handleTransaction(transaction, state);
            }
        });
    }

    private void handleTransaction(final ConsensusTransaction trans, final StatsSigningTestingToolState state) {
        final TransactionSignature s = trans.getMetadata();

        if (s != null && s.getSignatureStatus() != VerificationStatus.VALID) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Invalid Transaction Signature [ transactionId = {}, status = {}, signatureType = {},"
                            + " publicKey = {}, signature = {}, data = {} ]",
                    TransactionCodec.txId(trans.getApplicationTransaction()),
                    s.getSignatureStatus(),
                    s.getSignatureType(),
                    hex(s.getPublicKey()),
                    hex(s.getSignature()),
                    hex(s.getMessage()));
        }

        state.incrementRunningSum(TransactionCodec.txId(trans.getApplicationTransaction()));

        maybeDelay();
    }

    /**
     * Checks if the transaction bytes are system ones.
     *
     * @param transaction the transaction to check
     * @return true if the transaction bytes are system ones, false otherwise
     */
    private boolean areTransactionBytesSystemOnes(@NonNull final Transaction transaction) {
        final var transactionBytes = transaction.getApplicationTransaction();

        if (transactionBytes.length() == 0) {
            return false;
        }

        return transactionBytes.getByte(0) == SYSTEM_TRANSACTION_MARKER;
    }

    private void consumeSystemTransaction(
            @NonNull final Transaction transaction,
            @NonNull final Event event,
            @NonNull
                    final Consumer<ScopedSystemTransaction<StateSignatureTransaction>>
                            stateSignatureTransactionCallback) {
        try {
            final Bytes transactionBytes = transaction.getApplicationTransaction();
            final Bytes strippedSystemTransactionBytes = transactionBytes.slice(1, transactionBytes.length() - 1);
            final StateSignatureTransaction stateSignatureTransaction =
                    StateSignatureTransaction.PROTOBUF.parse(strippedSystemTransactionBytes);
            stateSignatureTransactionCallback.accept(new ScopedSystemTransaction<>(
                    event.getCreatorId(), event.getSoftwareVersion(), stateSignatureTransaction));
        } catch (final ParseException e) {
            logger.error("Failed to parse StateSignatureTransaction", e);
        }
    }

    private void maybeDelay() {
        if (SYNTHETIC_HANDLE_TIME) {
            final long start = System.nanoTime();
            while (System.nanoTime() - start < (HANDLE_MICROS * 1_000)) {
                // busy wait
            }
        }
    }

    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull StatsSigningTestingToolState state) {
        // No-op
        return true;
    }

    @Override
    public void onUpdateWeight(
            @NonNull StatsSigningTestingToolState state,
            @NonNull AddressBook configAddressBook,
            @NonNull PlatformContext context) {}

    @Override
    public void onNewRecoveredState(@NonNull StatsSigningTestingToolState recoveredState) {}
}
