// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.test.fixtures;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.crypto.TransactionSignature;

/**
 * Provides pre-generated random transactions that are optionally pre-signed with Ed25519 signatures.
 */
public class SignaturePool {

    /**
     * the length of signature in bytes
     */
    static final int SIGNATURE_LENGTH = Sign.BYTES;

    /**
     * the length of the public key in bytes
     */
    static final int PUBLIC_KEY_LENGTH = Sign.PUBLICKEYBYTES;

    /**
     * the length of the private key in bytes
     */
    static final int PRIVATE_KEY_LENGTH = Sign.SECRETKEYBYTES;

    /**
     * the length of the signature and the public key combined
     */
    static final int SIG_KEY_LENGTH = SIGNATURE_LENGTH + PUBLIC_KEY_LENGTH;
    /**
     * logs events related to the startup of the application
     */
    static final Marker LOGM_STARTUP = MarkerManager.getMarker("STARTUP");
    /**
     * use this for all logging, as controlled by the optional data/log4j2.xml file
     */
    private static final Logger logger = LogManager.getLogger(SignaturePool.class);

    private boolean transactionLogged = false;

    private int poolSize;

    /**
     * the list of transactions
     */
    private ArrayList<byte[]> transactions;

    /**
     * the fixed size of each transaction, not including the signature and public key
     */
    private int transactionSize;

    /**
     * indicates whether the transactions should be signed
     */
    private boolean signed;

    /**
     * the standard pseudo-random number generator
     */
    private Random random;

    /**
     * indicates whether there is an available algorithm implementation & keypair
     */
    private boolean algorithmAvailable;

    /**
     * the private key to use when signing each transaction
     */
    private byte[] privateKey;

    /**
     * the public key for each signed transaction
     */
    private byte[] publicKey;

    /**
     * the native NaCl signing interface
     */
    private Sign.Native signer;

    private AtomicInteger readPosition;

    /**
     * Constructs a SignaturePool instance with a fixed pool size, fixed transaction size, and whether to pre-sign each
     * transaction.
     *
     * @param poolSize
     * 		the number of pre-generated transactions
     * @param transactionSize
     * 		the size of randomly generated transaction
     * @param signed
     * 		whether to pre-sign each random transaction
     * @throws IllegalArgumentException
     * 		if the {@code poolSize} or the {@code transactionSize} parameters are less than
     * 		one (1)
     */
    public SignaturePool(final int poolSize, final int transactionSize, final boolean signed) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize");
        }

        if (transactionSize < 1) {
            throw new IllegalArgumentException("transactionSize");
        }

        this.random = new Random();
        this.signed = signed;

        this.transactionSize = transactionSize;
        this.transactions = new ArrayList<>(poolSize);
        this.poolSize = poolSize;
        this.readPosition = new AtomicInteger(0);

        this.algorithmAvailable = false;

        init();
    }

    /**
     * Retrieves a random transaction from the pool of pre-generated transactions.
     *
     * @return a random transaction from the pool
     */
    public TransactionSignature next() {
        int nextIdx = readPosition.getAndIncrement();

        if (nextIdx >= transactions.size()) {
            nextIdx = 0;
            readPosition.set(1);
        }

        final byte[] content = transactions.get(nextIdx);
        return new TransactionSignature(
                Bytes.wrap(content, 0, transactionSize),
                Bytes.wrap(content, transactionSize, PUBLIC_KEY_LENGTH),
                Bytes.wrap(content, transactionSize + PUBLIC_KEY_LENGTH, SIGNATURE_LENGTH),
                SignatureType.ED25519);
    }

    /**
     * Performs one-time initialization of this instance.
     */
    private void init() {
        if (signed) {
            tryAcquireSignature();
        }

        final int bufferSize = transactionSize + ((signed) ? SIG_KEY_LENGTH : 0);

        for (int i = 0; i < poolSize; i++) {
            final byte[] buffer = new byte[bufferSize];
            random.nextBytes(buffer);

            if (signed) {
                if (!algorithmAvailable) {
                    Arrays.fill(buffer, buffer.length - SIG_KEY_LENGTH, buffer.length, (byte) 0);
                } else {
                    sign(buffer);
                }
            }

            transactions.add(buffer);
        }
    }

    /**
     * Signs a transaction buffer where the first {@link #transactionSize} bytes represent the transaction data.
     *
     * @param buffer
     * 		the pre-allocated buffer of at least {@link #transactionSize} long, if {@link #signed} is true then
     * 		length must be at least ({@link #transactionSize} + {@link #SIG_KEY_LENGTH}) long
     * @throws SignatureException
     * 		if {@link Sign.Native#cryptoSignDetached(byte[], byte[], long, byte[])}
     * 		returns a failure code
     */
    private void sign(final byte[] buffer) {

        final int dataLength = buffer.length - SIG_KEY_LENGTH;

        try {
            int offset = dataLength;

            final byte[] data = new byte[dataLength];
            final byte[] sig = new byte[SIGNATURE_LENGTH];

            System.arraycopy(buffer, 0, data, 0, dataLength);
            System.arraycopy(publicKey, 0, buffer, offset, publicKey.length);
            offset += publicKey.length;

            if (!signer.cryptoSignDetached(sig, data, (long) data.length, privateKey)) {
                throw new SignatureException();
            }

            if (!transactionLogged) {
                logger.trace(
                        LOGM_STARTUP,
                        "StatsSigningDemo: Signed Message { publicKey = '{}', privateKey ='{}', signature = '{}', "
                                + "message = '{}' }",
                        hex(publicKey),
                        hex(privateKey),
                        hex(sig),
                        hex(data));

                transactionLogged = true;
            }

            System.arraycopy(sig, 0, buffer, offset, sig.length);
        } catch (Exception ex) {
            logger.error(EXCEPTION.getMarker(), "Adv Crypto Subsystem: Failed to sign transaction", ex);
        }
    }

    /**
     * Initializes the {@link #signer} instance and creates the public/private keys.
     */
    private void tryAcquireSignature() {
        try {
            final SodiumJava sodium = new SodiumJava();
            signer = new LazySodiumJava(sodium);

            publicKey = new byte[PUBLIC_KEY_LENGTH];
            privateKey = new byte[PRIVATE_KEY_LENGTH];

            algorithmAvailable = signer.cryptoSignKeypair(publicKey, privateKey);
            logger.trace(LOGM_STARTUP, "StatsSigningDemo: Public Key -> hex('{}')", () -> hex(publicKey));
        } catch (Exception ex) {
            algorithmAvailable = false;
        }
    }

    /**
     * Converts a byte array to a hexadecimal string.
     *
     * @param bytes
     * 		the bytes to convert to a hexadecimal string
     * @return a hex encoded string representation of the bytes
     */
    private String hex(final byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        sb.append("0x");

        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }

        return sb.toString();
    }
}
