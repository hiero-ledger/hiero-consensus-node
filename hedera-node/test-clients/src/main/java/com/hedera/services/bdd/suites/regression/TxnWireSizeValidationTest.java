// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.SECP256K1;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Transaction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * End-to-end coverage that the transaction-size checks and the jumbo per-second byte throttle bound a transaction
 * by its retained wire length. A transaction whose wire form is larger than its parsed size (a non-canonical
 * protobuf encoding, e.g. a duplicated {@code bodyBytes} field) must be treated the same as an honestly-sized one:
 * rejected by the size limit, and charged against the byte throttle.
 */
public class TxnWireSizeValidationTest {
    private static final int LARGE_TARGET = 133_000;
    private static final int EXTRA_SIGNATURE_PAIRS = 70;
    private static final String CALLDATA_SIZE = "CalldataSize";
    private static final String CALLME = "callme";
    private static final String TOKEN = "coin";
    private static final String TREASURY = "treasury";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String PAYER = "payer";

    @HapiTest
    @DisplayName("A non-canonically encoded mint is rejected like an honestly oversized one")
    final Stream<DynamicTest> nonCanonicalMintIsRejectedAsOversize() {
        return hapiTest(
                // A dedicated, ordinary payer. The framework's default payer falls inside the default
                // governanceTransactions.accountsRange (2,42-799), which is granted the larger 133,120-byte
                // allowance instead of the standard 6,144-byte one; an ordinary account avoids that entirely.
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TREASURY).payingWith(PAYER),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(0L)
                        .treasury(TREASURY)
                        .supplyKey(SUPPLY_KEY)
                        .payingWith(PAYER),
                // An ordinary mint is unaffected.
                mintToken(TOKEN, 1L).payingWith(PAYER),
                // The same mint honestly grown past the limit with extra signature pairs is rejected.
                mintToken(TOKEN, 1L)
                        .payingWith(PAYER)
                        .withTxnTransform(txn -> withExtraSignaturePairs(txn, EXTRA_SIGNATURE_PAIRS))
                        .hasPrecheck(TRANSACTION_OVERSIZE),
                // The same mint whose wire form is grown by a duplicate bodyBytes field must be rejected identically.
                mintToken(TOKEN, 1L)
                        .payingWith(PAYER)
                        .withTxnTransform(txn -> withLeadingDuplicateBodyBytes(txn, LARGE_TARGET))
                        .hasPrecheck(TRANSACTION_OVERSIZE),
                // ... and so must a second non-canonical shape, trailing scalar padding.
                mintToken(TOKEN, 1L)
                        .payingWith(PAYER)
                        .withTxnTransform(txn -> withTrailingScalarPadding(txn, LARGE_TARGET))
                        .hasPrecheck(TRANSACTION_OVERSIZE));
    }

    @LeakyHapiTest(overrides = {"jumboTransactions.maxBytesPerSec"})
    @DisplayName("A non-canonically encoded EthereumTransaction is charged against the per-second byte budget")
    final Stream<DynamicTest> nonCanonicalEthereumTransactionIsByteThrottled() {
        return hapiTest(
                overriding("jumboTransactions.maxBytesPerSec", "100"),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP256K1),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                uploadInitCode(CALLDATA_SIZE),
                contractCreate(CALLDATA_SIZE),
                // Empty inline call data; the wire form is grown to ~133 KB by a duplicate bodyBytes field. Its
                // excess over the regular 6,144-byte limit exceeds the 100-byte-per-second budget on its own.
                jumboEthCall()
                        .withTxnTransform(txn -> withLeadingDuplicateBodyBytes(txn, LARGE_TARGET))
                        .hasPrecheck(BUSY));
    }

    private static HapiEthereumCall jumboEthCall() {
        return ethereumCall(CALLDATA_SIZE, CALLME, (Object) new byte[0])
                .markAsJumboTxn()
                .signingWith(SECP_256K1_SOURCE_KEY)
                .payingWith(RELAYER)
                .gasLimit(1_350_000L)
                .fee(ONE_MILLION_HBARS)
                .noLogging();
    }

    /**
     * Prepends a second {@code SignedTransaction.bodyBytes} occurrence before the real one. bodyBytes is a scalar
     * bytes field, so protobuf keeps whichever is read last: the parsed body is unchanged and its signature still
     * verifies, while the wire form is larger by the leading occurrence.
     */
    private static Transaction withLeadingDuplicateBodyBytes(final Transaction txn, final int targetSize) {
        final var signedTx = signedTransactionBytesOf(txn);
        final var filler = new byte[Math.max(0, targetSize - signedTx.size() - 8)];
        final var leading = SignedTransaction.newBuilder()
                .setBodyBytes(ByteString.copyFrom(filler))
                .build()
                .toByteString();
        return rewrap(leading.concat(signedTx));
    }

    /** Appends occurrences of the {@code false}-valued scalar at field 3, two bytes each on the wire. */
    private static Transaction withTrailingScalarPadding(final Transaction txn, final int targetSize) {
        final var signedTx = signedTransactionBytesOf(txn);
        final var padding = new byte[2 * Math.max(0, (targetSize - signedTx.size()) / 2)];
        for (int i = 0; i < padding.length; i += 2) {
            padding[i] = 0x18; // field 3, varint wire type; the following zero byte is the value
        }
        return rewrap(signedTx.concat(ByteString.copyFrom(padding)));
    }

    /** Grows the same transaction by adding signature pairs, which the size checks have always counted. */
    private static Transaction withExtraSignaturePairs(final Transaction txn, final int pairs) {
        try {
            final var signedTx = SignedTransaction.parseFrom(signedTransactionBytesOf(txn));
            final SignatureMap.Builder sigMap = signedTx.getSigMap().toBuilder();
            for (int i = 0; i < pairs; i++) {
                final var prefix = new byte[32];
                prefix[0] = (byte) 0xEE;
                prefix[1] = (byte) i;
                sigMap.addSigPair(SignaturePair.newBuilder()
                        .setPubKeyPrefix(ByteString.copyFrom(prefix))
                        .setEd25519(ByteString.copyFrom(new byte[64]))
                        .build());
            }
            return rewrap(signedTx.toBuilder().setSigMap(sigMap).build().toByteString());
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Transaction rewrap(final ByteString serializedSignedTx) {
        return Transaction.newBuilder()
                .setSignedTransactionBytes(serializedSignedTx)
                .build();
    }

    private static ByteString signedTransactionBytesOf(final Transaction txn) {
        return txn.getSignedTransactionBytes().isEmpty()
                ? SignedTransaction.newBuilder()
                        .setBodyBytes(txn.getBodyBytes())
                        .setSigMap(txn.getSigMap())
                        .build()
                        .toByteString()
                : txn.getSignedTransactionBytes();
    }
}
