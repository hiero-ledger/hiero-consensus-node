// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType.EIP1559;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.WEIBARS_IN_A_TINYBAR;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAutoCreatedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall.fromSignedBytes;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromEcdsaFile.createAndLinkEcdsaKey;
import static com.hedera.services.bdd.spec.utilops.inventory.SpecKeyFromEcdsaFile.ecdsaFrom;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.esaulpaugh.headlong.util.Integers;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.utils.Signing;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Pins the canonical RLP encoding as the only accepted form of {@code EthereumTransactionBody.ethereum_data}.
 *
 * <p>EIP-2718 requires a transaction envelope to consume its entire input, and
 * {@code ethereum_transaction.proto} specifies {@code ethereum_data} as "the complete transaction data". Since a
 * record's {@code ethereum_hash} is the keccak256 of exactly those bytes, accepting a longer framing of the same
 * transaction would make the recorded hash depend on how the bytes were packaged rather than on the transaction
 * itself - two encodings of one transaction would settle under two different identities. Signer recovery does not
 * constrain this on its own, because {@code EthTxSigs.calculateSignableMessage()} re-encodes from the parsed
 * fields and so is indifferent to the surrounding framing.
 *
 * <p>These specs cover both directions end to end: a non-canonical encoding is refused at ingest and leaves the
 * account's nonce untouched, and the canonical encoding of the same transaction then settles normally under the
 * keccak256 of the bytes that were signed. Nothing is hard-coded - each transaction is built and signed in-spec,
 * and expected hashes are computed with Keccak directly rather than through the code under test. The two specs
 * use disjoint keys so they remain independent of ordering when sharing a network.
 */
@Tag(SMART_CONTRACT)
@SuppressWarnings("java:S5960")
public class EthereumTransactionCanonicalRlpTest {
    private static final int CHAIN_ID = 298;
    private static final long GAS_LIMIT = 100_000L;
    private static final long MAX_PRIORITY_GAS_WEIBAR = 1_000L;
    private static final long MAX_GAS_WEIBAR = 500_000_000_000L;
    private static final long TRANSFER_TINYBARS = ONE_HBAR;
    private static final byte TRAILING_BYTE = 0x42;

    private static final Sender REJECTION_SENDER = new Sender("rejectionSender", "rejectionRecipient", 0x1c0de);
    private static final Sender CANONICAL_SENDER = new Sender("canonicalSender", "canonicalRecipient", 0x2c0de);

    /**
     * An encoding carrying bytes past the end of the envelope is refused at ingest, the operation does not run,
     * and the nonce stays unspent - so the canonical encoding of the same transaction still settles afterwards,
     * under the keccak256 of the bytes that were signed.
     *
     * <p>The refusal is a precheck failure rather than a consensus status because {@code pureChecks} runs inside
     * {@code IngestChecker}, so a non-canonical encoding never reaches consensus.
     */
    @HapiTest
    final Stream<DynamicTest> trailingBytesAreRejectedAndLeaveTheNonceUnspent() {
        final var canonical = REJECTION_SENDER.canonicalSignedBytes();
        final var withTrailingByte = withTrailingByte(canonical);
        assertEveryOneByteTrailerIsRejected(canonical);

        return hapiTest(flattened(
                REJECTION_SENDER.setUp(),
                balanceSnapshot("rejectionRecipientBefore", REJECTION_SENDER.recipientKey())
                        .accountIsAlias(),
                fromSignedBytes(withTrailingByte)
                        .payingWith(REJECTION_SENDER.submitter())
                        .maxGasAllowance(10 * ONE_HBAR)
                        .hasPrecheck(INVALID_ETHEREUM_TRANSACTION),
                // Nothing was transferred and no nonce was consumed
                getAutoCreatedAccountBalance(REJECTION_SENDER.recipientKey())
                        .hasTinyBars(changeFromSnapshot("rejectionRecipientBefore", 0L)),
                getAliasedAccountInfo(REJECTION_SENDER.senderKey())
                        .has(accountWith().nonce(0L)),
                // So the canonical encoding remains usable, and records the hash of what was signed
                fromSignedBytes(canonical)
                        .payingWith(REJECTION_SENDER.submitter())
                        .maxGasAllowance(10 * ONE_HBAR)
                        .via("canonicalAfterRefusal")
                        .hasKnownStatus(SUCCESS),
                getTxnRecord("canonicalAfterRefusal")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .ethereumHash(ByteString.copyFrom(MiscCryptoUtils.keccak256DigestOf(canonical)))),
                getAutoCreatedAccountBalance(REJECTION_SENDER.recipientKey())
                        .hasTinyBars(changeFromSnapshot("rejectionRecipientBefore", TRANSFER_TINYBARS)),
                getAliasedAccountInfo(REJECTION_SENDER.senderKey())
                        .has(accountWith().nonce(1L))));
    }

    /**
     * The baseline: a transaction submitted as exact bytes settles and is recorded under
     * {@code keccak256(signedBytes)}. The only thing separating this from the refused case is the byte appended
     * past the envelope, which isolates the framing as the cause.
     */
    @HapiTest
    final Stream<DynamicTest> exactBytesSettleUnderTheHashOfTheSignedEncoding() {
        final var canonical = CANONICAL_SENDER.canonicalSignedBytes();

        return hapiTest(flattened(
                CANONICAL_SENDER.setUp(),
                balanceSnapshot("canonicalRecipientBefore", CANONICAL_SENDER.recipientKey())
                        .accountIsAlias(),
                fromSignedBytes(canonical)
                        .payingWith(CANONICAL_SENDER.submitter())
                        .maxGasAllowance(10 * ONE_HBAR)
                        .via("exactSubmission")
                        .hasKnownStatus(SUCCESS),
                getTxnRecord("exactSubmission")
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .ethereumHash(ByteString.copyFrom(MiscCryptoUtils.keccak256DigestOf(canonical)))),
                getAutoCreatedAccountBalance(CANONICAL_SENDER.recipientKey())
                        .hasTinyBars(changeFromSnapshot("canonicalRecipientBefore", TRANSFER_TINYBARS)),
                getAliasedAccountInfo(CANONICAL_SENDER.senderKey())
                        .has(accountWith().nonce(1L))));
    }

    /**
     * Checks off-network that the canonical bytes parse and that every one of the 256 possible single trailing
     * bytes is refused. Sweeping all of them matters because some values ({@code 00}, {@code 80}, {@code c0}) are
     * well-formed RLP items on their own and would be read as a further item, while others are malformed and
     * instead provoke a decoder error - the check has to cover both.
     */
    private static void assertEveryOneByteTrailerIsRejected(final byte[] canonical) {
        assertNotNull(EthTxData.populateEthTxData(canonical), "canonical bytes must parse");
        for (int i = 0; i < 256; i++) {
            final var variant = Arrays.copyOf(canonical, canonical.length + 1);
            variant[canonical.length] = (byte) i;
            final var trailer = i;
            assertNull(EthTxData.populateEthTxData(variant), () -> "a trailing 0x%02x must not be accepted"
                    .formatted(trailer));
        }
    }

    private static byte[] withTrailingByte(final byte[] canonical) {
        final var extended = Arrays.copyOf(canonical, canonical.length + 1);
        extended[canonical.length] = TRAILING_BYTE;
        return extended;
    }

    /// Left-pads `value` to a 32-byte secp256k1 private key.
    private static byte[] as32Bytes(final BigInteger value) {
        final var magnitude = EthTxData.asUnsignedByteArray(value);
        final var padded = new byte[32];
        System.arraycopy(magnitude, 0, padded, 32 - magnitude.length, magnitude.length);
        return padded;
    }

    /**
     * One EOA, the recipient of its transfer, and the ordinary account that pays for the HAPI wrapper. The
     * {@code seed} gives each spec disjoint EVM addresses, so their nonces are independent.
     */
    private record Sender(String senderKey, String recipientKey, int seed) {
        private BigInteger senderPrivateKey() {
            return BigInteger.valueOf(seed).shiftLeft(200).add(BigInteger.ONE);
        }

        private BigInteger recipientPrivateKey() {
            return BigInteger.valueOf(seed).shiftLeft(200).add(BigInteger.TWO);
        }

        String submitter() {
            return senderKey + "Submitter";
        }

        SpecOperation[] setUp() {
            return new SpecOperation[] {
                withOpContext((spec, opLog) -> {
                    createAndLinkEcdsaKey(
                            spec, ecdsaFrom(senderPrivateKey()), senderKey, Optional.empty(), Optional.empty(), opLog);
                    createAndLinkEcdsaKey(
                            spec,
                            ecdsaFrom(recipientPrivateKey()),
                            recipientKey,
                            Optional.empty(),
                            Optional.empty(),
                            opLog);
                }),
                cryptoCreate(submitter()).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, senderKey, ONE_HUNDRED_HBARS)),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, recipientKey, 1L)),
                getAliasedAccountInfo(senderKey).has(accountWith().nonce(0L))
            };
        }

        /** Builds and signs this sender's transaction, so the raw bytes are derived rather than hard-coded. */
        byte[] canonicalSignedBytes() {
            final var privateKey = as32Bytes(senderPrivateKey());
            final var to = EthSigsUtils.recoverAddressFromPrivateKey(as32Bytes(recipientPrivateKey()));
            final var unsigned = new EthTxData(
                    null, // rawTx - null so encodeTx() yields the canonical encoding
                    EIP1559,
                    Integers.toBytes(CHAIN_ID),
                    0L, // nonce
                    null, // gasPrice
                    Integers.toBytes(MAX_PRIORITY_GAS_WEIBAR),
                    Integers.toBytes(MAX_GAS_WEIBAR),
                    GAS_LIMIT,
                    to,
                    BigInteger.valueOf(TRANSFER_TINYBARS).multiply(WEIBARS_IN_A_TINYBAR),
                    new byte[0], // callData
                    new byte[0], // accessList
                    new Object[0],
                    null, // authorizationList
                    null,
                    0, // recId - replaced by signing
                    null, // v
                    new byte[0], // r - replaced by signing
                    new byte[0]); // s - replaced by signing
            return Signing.signMessage(unsigned, privateKey).encodeTx();
        }
    }
}
