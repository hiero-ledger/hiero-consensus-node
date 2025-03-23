// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats.signing;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.TransactionSignature;
import com.swirlds.demo.stats.signing.algorithms.ExtendedSignature;
import com.swirlds.demo.stats.signing.algorithms.SigningAlgorithm;
import com.swirlds.demo.stats.signing.algorithms.X25519SigningAlgorithm;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Provides pre-generated random transactions that are optionally pre-signed.
 */
final class SttTransactionPool {

    /**
     * use this for all logging, as controlled by the optional data/log4j2.xml file
     */
    private static final Logger logger = LogManager.getLogger(SttTransactionPool.class);

    /**
     * the array of transactions
     */
    private final byte[][] transactions;

    /**
     * the fixed size of each transaction, not including the signature and public key
     */
    private final int transactionSize;

    /**
     * indicates whether the transactions should be signed
     */
    private final boolean signed;

    /**
     * the standard pseudo-random number generator
     */
    private final Random random;

    private final List<SigningAlgorithm> enabledAlgorithms;

    private final Map<Byte, SigningAlgorithm> activeAlgorithms;

    private final NodeId nodeId;

    /**
     * Constructs a TransactionPool instance with a fixed pool size, fixed transaction size, and whether to pre-sign
     * each
     * transaction.
     *
     * @param poolSize
     * 		the number of pre-generated transactions
     * @param transactionSize
     * 		the size of randomly generated transaction
     * @param signed
     * 		whether to pre-sign each random transaction
     * @throws IllegalArgumentException
     * 		if the {@code poolSize} or the {@code transactionSize} parameters are less than one (1)
     */
    SttTransactionPool(
            @NonNull final NodeId nodeId,
            final int poolSize,
            final int transactionSize,
            final boolean signed,
            @Nullable final SigningAlgorithm... enabledAlgorithms) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize");
        }

        if (transactionSize < 1) {
            throw new IllegalArgumentException("transactionSize");
        }

        this.random = new Random();
        this.signed = signed;

        this.nodeId = Objects.requireNonNull(nodeId);
        this.transactionSize = transactionSize;
        this.transactions = new byte[poolSize][];
        this.enabledAlgorithms = new ArrayList<>();
        this.activeAlgorithms = new HashMap<>();

        if (enabledAlgorithms != null && enabledAlgorithms.length > 0) {
            this.enabledAlgorithms.addAll(Arrays.asList(enabledAlgorithms));
        } else {
            this.enabledAlgorithms.add(new X25519SigningAlgorithm());
        }

        init();
    }

    TransactionSignature expandSignatures(final Bytes tx) {
        if (!TransactionCodec.txIsSigned(tx)) {
            return null;
        }

        return TransactionCodec.extractSignature(tx);
    }

    /**
     * Retrieves a random transaction from the pool of pre-generated transactions.
     *
     * @return a random transaction from the pool
     */
    byte[] transaction() {
        return transactions[random.nextInt(transactions.length)];
    }

    /**
     * Performs one-time initialization of this instance.
     */
    private void init() {
        if (signed) {
            tryAcquirePrimitives();
        }

        final List<SigningAlgorithm> algorithms = new ArrayList<>(this.activeAlgorithms.values());
        int lastChosenAlg = 0;
        long transactionId = nodeId.id() * transactions.length;

        for (int i = 0; i < transactions.length; i++) {
            final byte[] data = new byte[transactionSize];
            random.nextBytes(data);

            if (signed && !algorithms.isEmpty()) {
                final SigningAlgorithm alg = algorithms.get(lastChosenAlg);
                lastChosenAlg++;

                if (lastChosenAlg >= algorithms.size()) {
                    lastChosenAlg = 0;
                }

                try {
                    final ExtendedSignature exSig = alg.signEx(data, 0, data.length);
                    final byte[] sig = exSig.getSignature();

                    transactions[i] = TransactionCodec.encode(alg, transactionId, sig, data);
                } catch (final SignatureException e) {
                    // If we are unable to sign the transaction then log the failure and create an unsigned transaction
                    logger.error(
                            EXCEPTION.getMarker(),
                            "Failed to Sign Transaction (Proceeding As Unsigned) [ id = {} ]",
                            transactionId,
                            e);
                    transactions[i] = TransactionCodec.encode(null, transactionId, null, data);
                }
            } else {
                transactions[i] = TransactionCodec.encode(null, transactionId, null, data);
            }

            transactionId++;
        }
    }

    /**
     * Initializes the {@link #activeAlgorithms} map and creates the public/private keys.
     */
    private void tryAcquirePrimitives() {
        for (final SigningAlgorithm algorithm : enabledAlgorithms) {
            try {
                if (algorithm == null) {
                    continue;
                }

                if (activeAlgorithms.containsKey(algorithm.getId())) {
                    throw new IllegalStateException(
                            String.format("Duplicate signing algorithm specified [ id = %s ]", algorithm.getId()));
                }

                algorithm.tryAcquirePrimitives();

                if (algorithm.isAvailable()) {
                    activeAlgorithms.put(algorithm.getId(), algorithm);
                }
            } catch (final Exception ex) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "Failed to Activate Signing Algorithm [ id = {}, class = {} ]",
                        algorithm.getId(),
                        algorithm.getClass().getName(),
                        ex);
            }
        }
    }
}
