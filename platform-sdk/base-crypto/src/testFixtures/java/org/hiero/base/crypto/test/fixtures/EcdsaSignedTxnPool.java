// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.test.fixtures;

import static org.hiero.base.crypto.engine.EcdsaSecp256k1Verifier.ECDSA_KECCAK_256_SIZE;
import static org.hiero.base.crypto.engine.EcdsaSecp256k1Verifier.ECDSA_UNCOMPRESSED_KEY_SIZE;
import static org.hiero.base.crypto.test.fixtures.EcdsaUtils.asRawEcdsaSecp256k1Key;
import static org.hiero.base.crypto.test.fixtures.EcdsaUtils.signDigestWithEcdsaSecp256k1;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.crypto.TransactionSignature;

/**
 * Provides pre-generated random transactions that are optionally pre-signed with ECDSA(secp256k1) signatures.
 */
public class EcdsaSignedTxnPool {

    /**
     * the length of the public key in bytes
     */
    private static final int PUBLIC_KEY_LEN = ECDSA_UNCOMPRESSED_KEY_SIZE;

    private static class SignedTxn {
        private final int sigLen;
        private final byte[] txn;

        public SignedTxn(final int sigLen, final byte[] txn) {
            this.sigLen = sigLen;
            this.txn = txn;
        }
    }

    int poolSize;
    int transactionSize;
    boolean algorithmAvailable;
    AtomicInteger readPosition;
    SplittableRandom random = new SplittableRandom();
    ArrayList<SignedTxn> signedTxns;

    /* Used to share a generated keypair between instance methods */
    private KeyPair activeKp;

    /**
     * Constructs a EcdsaSignedTxnPool instance with a fixed pool size and transaction size.
     *
     * @param poolSize
     * 		the number of pre-generated transactions
     * @param transactionSize
     * 		the size of randomly generated transaction
     */
    public EcdsaSignedTxnPool(final int poolSize, final int transactionSize) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize");
        }
        if (transactionSize < 1) {
            throw new IllegalArgumentException("transactionSize");
        }

        this.poolSize = poolSize;
        this.transactionSize = transactionSize;

        this.signedTxns = new ArrayList<>(poolSize);
        this.readPosition = new AtomicInteger(0);

        this.algorithmAvailable = false;

        init();
    }

    /**
     * Retrieves a random transaction from the pool of pre-generated transactions, resetting its
     * attached signature so other tests can use the pool to exercise signature verification.
     *
     * @return a random transaction from the pool, with one signature with UNKNOWN status
     */
    public TransactionSignature next() {
        int nextIdx = readPosition.getAndIncrement();

        if (nextIdx >= signedTxns.size()) {
            nextIdx = 0;
            readPosition.set(1);
        }

        final SignedTxn signedTxn = signedTxns.get(nextIdx);
        final byte[] content = signedTxn.txn;
        return new TransactionSignature(
                Bytes.wrap(content, 0, ECDSA_KECCAK_256_SIZE),
                Bytes.wrap(content, ECDSA_KECCAK_256_SIZE, PUBLIC_KEY_LEN),
                Bytes.wrap(content, ECDSA_KECCAK_256_SIZE + PUBLIC_KEY_LEN, signedTxn.sigLen),
                SignatureType.ECDSA_SECP256K1);
    }

    /**
     * Initialization for the transaction pool
     */
    void init() {
        generateActiveKeyPair();

        final byte[] activePubKey = asRawEcdsaSecp256k1Key((ECPublicKey) activeKp.getPublic());
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance("KECCAK-256");
            for (int i = 0; i < poolSize; i++) {
                final byte[] rawMsg = new byte[transactionSize];
                random.nextBytes(rawMsg);
                final byte[] msg = messageDigest.digest(rawMsg);
                final byte[] sig = signDigestWithEcdsaSecp256k1(activeKp.getPrivate(), msg);

                final byte[] buffer = new byte[transactionSize + sig.length + activePubKey.length];
                System.arraycopy(msg, 0, buffer, 0, msg.length);
                System.arraycopy(activePubKey, 0, buffer, msg.length, activePubKey.length);
                System.arraycopy(sig, 0, buffer, msg.length + activePubKey.length, sig.length);

                final SignedTxn signedTxn = new SignedTxn(sig.length, buffer);
                signedTxns.add(signedTxn);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Generate an ECDSASecp256K1 keypair
     */
    void generateActiveKeyPair() {
        try {
            activeKp = EcdsaUtils.genEcdsaSecp256k1KeyPair();
        } catch (final Exception fatal) {
            throw new IllegalStateException("Tests cannot be trusted without working key-pair generation", fatal);
        }
    }
}
