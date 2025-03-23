// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.FREEZE;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.google.protobuf.ByteString;
import com.swirlds.common.FastCopyable;
import com.swirlds.common.test.fixtures.crypto.ECDSASigningProvider;
import com.swirlds.common.test.fixtures.crypto.ED25519SigningProvider;
import com.swirlds.common.test.fixtures.crypto.SigningProvider;
import com.swirlds.demo.merkle.map.FCMConfig;
import com.swirlds.demo.merkle.map.FCMTransactionPool;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.platform.freeze.FreezeConfig;
import com.swirlds.demo.platform.fs.stresstest.proto.AppTransactionSignatureType;
import com.swirlds.demo.platform.fs.stresstest.proto.ControlTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.ControlType;
import com.swirlds.demo.platform.fs.stresstest.proto.FreezeTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.RandomBytesTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.SimpleAction;
import com.swirlds.demo.platform.fs.stresstest.proto.TestTransaction;
import com.swirlds.demo.platform.fs.stresstest.proto.TestTransactionWrapper;
import com.swirlds.demo.platform.iss.IssConfig;
import com.swirlds.demo.virtualmerkle.config.VirtualMerkleConfig;
import com.swirlds.demo.virtualmerkle.transaction.pool.VirtualMerkleTransactionPool;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.system.Platform;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.consensus.model.node.NodeId;

/**
 * Provides pre-generated random transactions that are optionally pre-signed with Ed25519 signatures.
 */
public class PttTransactionPool implements FastCopyable {
    /**
     * use this for all logging, as controlled by the optional data/log4j2.xml file
     */
    private static final Logger logger = LogManager.getLogger(PttTransactionPool.class);

    private static final Marker MARKER = MarkerManager.getMarker("DEMO_INFO");
    private static final Marker ERROR = MarkerManager.getMarker("EXCEPTION");
    /** Transaction pool for FCM operations */
    private FCMTransactionPool fcmTransactionPool;

    private VirtualMerkleTransactionPool virtualMerkleTransactionPool;

    Platform platform;
    private final TransactionSubmitter submitter;
    private boolean transactionLogged = false;

    /**
     * indicates whether the transactions should be signed
     */
    private final boolean signed;

    ///////////////////////////////////////////
    // Copyable variables
    private final PayloadConfig config;
    private final TransactionPoolConfig transactionPoolConfig;
    /**
     * the standard pseudo-random number generator
     */
    private final Random random;

    private final IssConfig issConfig;
    private final Instant initTime;
    /** Transaction pool for FCM operations */
    private Map<AppTransactionSignatureType, SigningProvider> signingProviderMap = new HashMap<>();

    private long nextSeq = 0; // only used for random bytes payload
    private long nextFileDirSeq = 0;
    private long[] sequentialTestCount = null;
    private int sequentialTypeIndex = 0;
    private FreezeConfig freezeConfig;
    private boolean needToSubmitFreezeTx = false;
    private boolean issTransactionSent = false;

    private PttTransactionPool(final PttTransactionPool sourcePool) {
        this.virtualMerkleTransactionPool = sourcePool.virtualMerkleTransactionPool;
        this.fcmTransactionPool = sourcePool.fcmTransactionPool.copy();
        this.platform = sourcePool.platform;
        this.submitter = sourcePool.submitter;
        this.transactionLogged = sourcePool.transactionLogged;
        this.signed = sourcePool.signed;
        this.config = sourcePool.config;
        this.transactionPoolConfig = sourcePool.transactionPoolConfig;
        this.random = sourcePool.random;

        this.nextSeq = sourcePool.nextSeq;
        this.nextFileDirSeq = sourcePool.nextFileDirSeq;
        if (sourcePool.sequentialTestCount != null) {
            this.sequentialTestCount =
                    Arrays.copyOf(sourcePool.sequentialTestCount, sourcePool.sequentialTestCount.length);
        }

        this.sequentialTypeIndex = sourcePool.sequentialTypeIndex;
        this.freezeConfig = sourcePool.freezeConfig;
        this.needToSubmitFreezeTx = sourcePool.needToSubmitFreezeTx;
        this.issConfig = sourcePool.issConfig;
        this.issTransactionSent = sourcePool.issTransactionSent;
        this.initTime = sourcePool.initTime;
        this.signingProviderMap = sourcePool.signingProviderMap;
    }

    /**
     * Constructs a TransactionPool instance with a fixed pool size, fixed transaction size, and whether to pre-sign
     * each transaction.
     *
     * @param config
     * 		configuration related with payload type and size
     * @param myName
     * 		this node name
     */
    public PttTransactionPool(
            final Platform platform,
            final long myID,
            final PayloadConfig config,
            final String myName,
            final FCMConfig fcmConfig,
            final VirtualMerkleConfig virtualMerkleConfig,
            final FreezeConfig freezeConfig,
            final TransactionPoolConfig transactionPoolConfig,
            final TransactionSubmitter submitter,
            final ExpectedFCMFamily expectedFCMFamily,
            final IssConfig issConfig) {
        this.random = new Random();
        this.config = config;
        this.transactionPoolConfig = transactionPoolConfig;
        this.signed = config.isAppendSig();
        this.submitter = submitter;
        this.platform = platform;
        this.initTime = Instant.now();

        if (fcmConfig != null) {
            fcmTransactionPool =
                    new FCMTransactionPool(platform, myID, fcmConfig, submitter, this, expectedFCMFamily, config);
        }

        if (virtualMerkleConfig != null) {
            virtualMerkleTransactionPool =
                    new VirtualMerkleTransactionPool(myID, virtualMerkleConfig, expectedFCMFamily);
        }

        /** If the startFreezeAfterMin is 0, we don't send freeze transaction */
        if (freezeConfig != null
                && Objects.equals(platform.getSelfId(), NodeId.of(0L))
                && freezeConfig.getStartFreezeAfterMin() > 0) {
            this.freezeConfig = freezeConfig;
            this.needToSubmitFreezeTx = true;
        }
        this.issConfig = issConfig;

        signingProviderMap.put(AppTransactionSignatureType.ECDSA_SECP256K1, new ECDSASigningProvider());
        signingProviderMap.put(AppTransactionSignatureType.ED25519, new ED25519SigningProvider());
    }

    public PttTransactionPool(
            final Platform platform,
            final long myID,
            final PayloadConfig config,
            final String myName,
            final FCMConfig fcmConfig,
            final VirtualMerkleConfig virtualMerkleConfig,
            final FreezeConfig freezeConfig,
            final TransactionPoolConfig transactionPoolConfig,
            final TransactionSubmitter submitter,
            final ExpectedFCMFamily expectedFCMFamily) {
        this(
                platform,
                myID,
                config,
                myName,
                fcmConfig,
                virtualMerkleConfig,
                freezeConfig,
                transactionPoolConfig,
                submitter,
                expectedFCMFamily,
                null);
    }

    /**
     * generates a random integer in a range between min (inclusive) and max (inclusive).
     */
    private static int getRandomNumberInRange(final int min, final int max) {

        if (min >= max) {
            throw new IllegalArgumentException("max must be greater than min");
        }

        final Random r = new Random();
        return r.nextInt((max - min) + 1) + min;
    }

    /**
     * decides whether to generate an invalid signature for current FCMTransaction,
     * by generating a random value in range [0.0, 1.0).
     * if it is less than defined invalidSigRatio,
     * we will generate invalid signature for this FCMTransaction
     */
    boolean invalidSig() {
        return Math.random() < this.config.getInvalidSigRatio();
    }

    /**
     * Whether generate a FCM transaction or a virtual merkle transaction
     */
    boolean isFCMTransaction() {
        if (Math.random() < this.config.getRatioOfFCMTransaction()) {
            return true;
        }
        return false;
    }

    /**
     * Retrieves a random transaction from the pool of pre-generated transactions.
     *
     * @return a random transaction from the pool
     */
    Triple<byte[], PAYLOAD_TYPE, MapKey> transaction() {
        // If FreezeConfig has been set, we need to submit a FreezeTransaction
        if (this.needToSubmitFreezeTx) {
            this.needToSubmitFreezeTx = false;
            logger.info(FREEZE.getMarker(), "Node {} submits a Freeze Transaction", platform.getSelfId());
            final byte[] freezeLoad = createFreezeTranBytes(freezeConfig);
            return Triple.of(freezeLoad, PAYLOAD_TYPE.TYPE_TEST_SYNC, null);
        }

        if (this.issConfig != null && !issTransactionSent && this.issConfig.shouldSendIssTransaction(initTime)) {
            issTransactionSent = true;
            logger.info(STARTUP.getMarker(), "Sending ISS transaction");
            return Triple.of(
                    appendSignature(
                            TestTransaction.newBuilder()
                                    .setSimpleAction(SimpleAction.CAUSE_ISS)
                                    .build()
                                    .toByteArray(),
                            false),
                    PAYLOAD_TYPE.TYPE_TEST_SYNC,
                    null);
        }

        byte[] payload = null;
        // if it is true, will generate invalid signature for FCMTransaction
        boolean invalidSig = false;

        Triple<byte[], PAYLOAD_TYPE, MapKey> payloadPair = null;

        PAYLOAD_TYPE generateType = PAYLOAD_TYPE.TYPE_RANDOM_BYTES;
        int bufferSize = config.getPayloadByteSize();

        final PayloadDistribution distribution = config.getPayloadDistribution();
        if (distribution != null) {
            final PayloadProperty property = distribution.getPayloadProperty(random.nextInt(10000) / 100.0f);
            generateType = property.getType();
            bufferSize = property.getSize();
        } else {
            generateType = config.getType();
        }

        if (generateType == PAYLOAD_TYPE.TYPE_RANDOM_BYTES) {
            if (config.isVariedSize()) {
                bufferSize = getRandomNumberInRange(config.getPayloadByteSize(), config.getMaxByteSize());
            }

            final byte[] ramdomBytesPayload = new byte[bufferSize];
            random.nextBytes(ramdomBytesPayload); // fill random bytes

            if (config.isInsertSeq()) { // add sequence if required
                final byte[] seq = Utilities.toBytes(nextSeq);
                System.arraycopy(seq, 0, ramdomBytesPayload, 0, seq.length);
                nextSeq++;
            }

            final RandomBytesTransaction bytesTransaction = RandomBytesTransaction.newBuilder()
                    .setIsInserSeq(config.isInsertSeq())
                    .setData(ByteString.copyFrom(ramdomBytesPayload))
                    .build();

            final TestTransaction testTransaction = TestTransaction.newBuilder()
                    .setBytesTransaction(bytesTransaction)
                    .build();

            payload = testTransaction.toByteArray();
            payloadPair = Triple.of(payload, generateType, null);
        } else if (generateType == PAYLOAD_TYPE.TYPE_FCM_TEST) {
            invalidSig = invalidSig();
            payloadPair = fcmTransactionPool.getTransaction(invalidSig);
            if (payloadPair != null) {
                payload = payloadPair.left();
            }
        } else if (generateType == PAYLOAD_TYPE.TYPE_VIRTUAL_MERKLE_TEST) {
            payloadPair = virtualMerkleTransactionPool.getTransaction();
            if (payloadPair != null) {
                payload = payloadPair.left();
            }
        } else if (generateType == PAYLOAD_TYPE.TYPE_FCM_VIRTUAL_MIX) {
            if (isFCMTransaction()) {
                invalidSig = invalidSig();
                payloadPair = fcmTransactionPool.getTransaction(invalidSig);
                if (payloadPair != null) {
                    payload = payloadPair.left();
                }
            }
            // payloadPair still null indicates it's the turn to generate virtual merkle transaction
            // or no more FCM transaction
            if (payloadPair == null) {
                payloadPair = virtualMerkleTransactionPool.getTransaction();
                if (payloadPair != null) {
                    payload = payloadPair.left();
                } else {
                    logger.info(MARKER, "Generated enough virtual merkle test for sequential mode");
                }
            }
        }

        if (payload != null && config.isAppendSig()) { // if require signature add more buffer space
            payloadPair = Triple.of(appendSignature(payload, invalidSig), payloadPair.middle(), payloadPair.right());
        }

        return payloadPair;
    }

    /**
     * Append public key, signature and signature type for Message and FreezeTransaction
     *
     * @param payload
     * 		to be signed
     * @param invalidSig
     * 		whether the signature is invalid
     * @return concatenate byte arrays of payload, public key, signature and signature Type
     */
    public byte[] appendSignature(final byte[] payload, final boolean invalidSig) {
        final AppTransactionSignatureType signatureType = transactionPoolConfig == null
                ? AppTransactionSignatureType.ED25519
                : transactionPoolConfig.getRandomSigType();
        if (payload != null && config.isAppendSig()) { // if require signature add more buffer space
            return signAndConcatenatePubKeySignature(payload, invalidSig, signatureType);
        }
        return payload;
    }

    public byte[] createControlTranBytes(final ControlType type) {
        final ControlTransaction msg =
                ControlTransaction.newBuilder().setType(type).build();
        final TestTransaction testTransaction =
                TestTransaction.newBuilder().setControlTransaction(msg).build();

        final byte[] data = testTransaction.toByteArray();

        return appendSignature(data, false);
    }

    public byte[] createFreezeTranBytes(final FreezeConfig freezeConfig) {
        final Instant startFreezeTime = this.initTime.plus(freezeConfig.getStartFreezeAfterMin(), ChronoUnit.MINUTES);
        return createFreezeTranByte(startFreezeTime);
    }

    public byte[] createFreezeTranByte(final Instant startFreezeTime) {
        final FreezeTransaction msg = FreezeTransaction.newBuilder()
                .setStartTimeEpochSecond(startFreezeTime.getEpochSecond())
                .build();
        final TestTransaction testTransaction =
                TestTransaction.newBuilder().setFreezeTransaction(msg).build();

        final byte[] data = testTransaction.toByteArray();

        return appendSignature(data, false);
    }

    public void setNextFileDirSeq(final long newValue) {
        nextFileDirSeq = newValue;
        logger.info(DEMO_INFO.getMarker(), "Set nextFileDirSeq {} ", nextFileDirSeq);
    }

    /**
     * Signs a transaction buffer, then build a TestTransactionWrapper with data payload,
     * public key, signature, and signature type, lastly return the raw bytes of TestTransactionWrapper.
     *
     * @param data
     * 		the raw message payload need to be signed
     * @param invalid
     * 		whether to generate invalid signature
     */
    byte[] signAndConcatenatePubKeySignature(
            final byte[] data, final boolean invalid, final AppTransactionSignatureType signatureType) {
        try {
            final SigningProvider signingProvider = signingProviderMap.get(signatureType);
            final byte[] sig = signingProvider.sign(data);

            // modify the first byte of valid signature, for generating invalid signature
            if (invalid) {
                final byte firstByte = sig[0];
                final byte modified = (byte) ~firstByte;
                sig[0] = modified;
            }

            if (!transactionLogged) {
                logger.trace(
                        STARTUP.getMarker(),
                        "Signed Message { signatureType = '{}', publicKey = '{}', privateKey ='{}', signature = '{}',"
                                + "message = '{}' }",
                        () -> signatureType,
                        () -> hex(signingProvider.getPublicKeyBytes()),
                        () -> hex(signingProvider.getPublicKeyBytes()),
                        () -> hex(sig),
                        () -> hex(data));

                transactionLogged = true;
            }

            final TestTransactionWrapper testTransactionWrapper = TestTransactionWrapper.newBuilder()
                    .setTestTransactionRawBytes(ByteString.copyFrom(data))
                    .setSignaturesRawBytes(ByteString.copyFrom(sig))
                    .setPublicKeyRawBytes(ByteString.copyFrom(signingProvider.getPublicKeyBytes()))
                    .setSignatureType(signatureType)
                    .build();

            return testTransactionWrapper.toByteArray();
        } catch (final Exception ex) {
            logger.error(EXCEPTION.getMarker(), "Failed to sign transaction", ex);
            return null;
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
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        sb.append("0x");

        for (final byte b : bytes) {
            sb.append(String.format("%02X", b));
        }

        return sb.toString();
    }

    @Override
    public PttTransactionPool copy() {
        throwIfImmutable();
        return new PttTransactionPool(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return false;
    }
}
