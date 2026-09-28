// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.spi.validation.AttributeValidator.MAX_NESTED_KEY_LEVELS;
import static com.hedera.pbj.runtime.Codec.DEFAULT_MAX_DEPTH;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BAD_ENCODING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_TOO_MANY_LAYERS;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class DeeplyNestedKeyListSuite {
    private static final int MAX_TRANSACTION_BYTES = 6 * 1024;
    // Over the semantic key-depth cap (MAX_NESTED_KEY_LEVELS) but within the node's proto-depth parse cap, so it
    // parses at ingest and is instead rejected at handle with BAD_ENCODING.
    private static final int OVER_SEMANTIC_WITHIN_PROTO_DEPTH_KEY_LIST_LEVELS = MAX_NESTED_KEY_LEVELS + 5;
    // HAPI re-parses submitted transactions with Google protobuf while resolving status, so stay below its recursion
    // limit while still exceeding the node's proto-depth parse cap (rejected at ingest with
    // TRANSACTION_TOO_MANY_LAYERS).
    private static final int OVER_PROTO_DEPTH_CLIENT_PARSEABLE_KEY_LIST_LEVELS = 40;
    private static final int PBJ_MESSAGE_FRAMES_PER_KEY_LIST_LEVEL = 2;
    // Derived from PBJ's built-in DEFAULT_MAX_DEPTH, NOT the node's stricter hedera.transaction.maxProtoMessageDepth
    // (default 50). The raw over-depth test nests one level past PBJ's own default, which is comfortably beyond the
    // node cap, so it is rejected at ingest with TRANSACTION_TOO_MANY_LAYERS.
    private static final int DEEPEST_KEY_LIST_LEVELS_WITHIN_PBJ_DEFAULT_DEPTH =
            DEFAULT_MAX_DEPTH / PBJ_MESSAGE_FRAMES_PER_KEY_LIST_LEVEL;
    private static final int KEY_LIST_LEVELS_PAST_PBJ_DEFAULT_DEPTH =
            DEEPEST_KEY_LIST_LEVELS_WITHIN_PBJ_DEFAULT_DEPTH + 1;
    private static final int TRANSACTION_BODY_TRANSACTION_ID_TAG = 10;
    private static final int TRANSACTION_BODY_NODE_ACCOUNT_ID_TAG = 18;
    private static final int TRANSACTION_BODY_TRANSACTION_FEE_TAG = 24;
    private static final int TRANSACTION_BODY_VALID_DURATION_TAG = 34;
    private static final int TRANSACTION_BODY_CRYPTO_CREATE_ACCOUNT_TAG = 90;
    private static final int CRYPTO_CREATE_KEY_TAG = 10;
    private static final int KEY_LIST_TAG = 50;
    private static final int KEY_LIST_KEYS_TAG = 10;
    private static final int ED25519_TAG = 18;
    private static final byte[] ED25519_KEY = lengthDelimited(ED25519_TAG, new byte[32]);
    private static final ByteString VALID_ED25519_KEY = ByteString.copyFrom(new byte[32]);

    @HapiTest
    final Stream<DynamicTest> acceptsKeyListAtSemanticDepthLimit() {
        return hapiTest(cryptoCreate("semanticallyAllowedNestedKeyList")
                .key(deepKeyList(MAX_NESTED_KEY_LEVELS - 1))
                .payingWith(GENESIS)
                .signedBy(GENESIS)
                .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsKeyOverSemanticDepthAtHandle() {
        // Parses at ingest (within the proto-depth cap) but exceeds MAX_NESTED_KEY_LEVELS, so the handler rejects it.
        return hapiTest(cryptoCreate("overSemanticNestedKeyList")
                .key(deepKeyList(OVER_SEMANTIC_WITHIN_PROTO_DEPTH_KEY_LIST_LEVELS))
                .payingWith(GENESIS)
                .signedBy(GENESIS)
                .hasPrecheck(BAD_ENCODING));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsKeyOverProtoDepthAtIngestWithTooManyLayers() {
        final var deeplyNestedKeyList = deepKeyList(OVER_PROTO_DEPTH_CLIENT_PARSEABLE_KEY_LIST_LEVELS);
        if (deeplyNestedKeyList.getSerializedSize() > MAX_TRANSACTION_BYTES) {
            throw new IllegalStateException("Deeply nested key list exceeds max transaction bytes");
        }

        return hapiTest(cryptoCreate("overProtoDepthNestedKeyList")
                .key(deeplyNestedKeyList)
                .payingWith(GENESIS)
                .signedBy(GENESIS)
                .hasPrecheck(TRANSACTION_TOO_MANY_LAYERS));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsRawOverDepthKeyWithTooManyLayers() {
        return hapiTest(
                new RawCryptoCreateWithPbjOverDepthKey().noLogging().hasPrecheckFrom(TRANSACTION_TOO_MANY_LAYERS));
    }

    private static Key deepKeyList(final int additionalKeyListLevels) {
        var nestedKey = Key.newBuilder().setEd25519(VALID_ED25519_KEY).build();
        for (int i = 0; i < additionalKeyListLevels; i++) {
            nestedKey = Key.newBuilder()
                    .setKeyList(KeyList.newBuilder().addKeys(nestedKey))
                    .build();
        }
        return nestedKey;
    }

    private static final class RawCryptoCreateWithPbjOverDepthKey
            extends HapiTxnOp<RawCryptoCreateWithPbjOverDepthKey> {
        @Override
        public HederaFunctionality type() {
            return CryptoCreate;
        }

        @Override
        protected RawCryptoCreateWithPbjOverDepthKey self() {
            return this;
        }

        @Override
        protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) {
            return ignored -> {};
        }

        @Override
        protected Transaction finalizedTxn(final HapiSpec spec, final Consumer<TransactionBody.Builder> opDef) {
            return cryptoCreateWithRawKey(spec, keyListNest(KEY_LIST_LEVELS_PAST_PBJ_DEFAULT_DEPTH));
        }
    }

    private static Transaction cryptoCreateWithRawKey(final HapiSpec spec, final byte[] keyBytes) {
        final var bodyBytes = cryptoCreateBodyWithRawKey(spec, keyBytes);
        final var signedTransaction = SignedTransaction.newBuilder()
                .setBodyBytes(ByteString.copyFrom(bodyBytes))
                .setSigMap(SignatureMap.getDefaultInstance())
                .build();
        final var transaction = Transaction.newBuilder()
                .setSignedTransactionBytes(signedTransaction.toByteString())
                .build();

        assertThat(bodyBytes).hasSizeLessThanOrEqualTo(MAX_TRANSACTION_BYTES);
        assertThat(signedTransaction.getSerializedSize()).isLessThanOrEqualTo(MAX_TRANSACTION_BYTES);
        assertThat(transaction.getSerializedSize()).isLessThanOrEqualTo(MAX_TRANSACTION_BYTES);
        return transaction;
    }

    private static byte[] cryptoCreateBodyWithRawKey(final HapiSpec spec, final byte[] keyBytes) {
        final var transactionId = TransactionID.newBuilder()
                .setAccountID(spec.setup().genesisAccount())
                .setTransactionValidStart(validStart())
                .build()
                .toByteArray();
        final var validDuration = Duration.newBuilder().setSeconds(120).build().toByteArray();
        final var cryptoCreate = lengthDelimited(CRYPTO_CREATE_KEY_TAG, keyBytes);

        final var out = new ByteArrayOutputStream();
        writeLengthDelimited(out, TRANSACTION_BODY_TRANSACTION_ID_TAG, transactionId);
        writeLengthDelimited(
                out,
                TRANSACTION_BODY_NODE_ACCOUNT_ID_TAG,
                spec.setup().defaultNode().toByteArray());
        writeVarIntField(out, TRANSACTION_BODY_TRANSACTION_FEE_TAG, 100_000_000L);
        writeLengthDelimited(out, TRANSACTION_BODY_VALID_DURATION_TAG, validDuration);
        writeLengthDelimited(out, TRANSACTION_BODY_CRYPTO_CREATE_ACCOUNT_TAG, cryptoCreate);
        return out.toByteArray();
    }

    private static Timestamp validStart() {
        final var now = Instant.now().minusSeconds(5);
        return Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
    }

    private static byte[] keyListNest(final int nestingLevels) {
        byte[] bytes = ED25519_KEY;
        for (int i = 0; i < nestingLevels; i++) {
            bytes = keyWithSingleNestedKeyList(bytes);
        }
        return bytes;
    }

    private static byte[] keyWithSingleNestedKeyList(final byte[] nestedKey) {
        final var keyList = lengthDelimited(KEY_LIST_KEYS_TAG, nestedKey);
        return lengthDelimited(KEY_LIST_TAG, keyList);
    }

    private static byte[] lengthDelimited(final int tag, final byte[] contents) {
        final var out = new ByteArrayOutputStream(1 + varIntSize(contents.length) + contents.length);
        writeLengthDelimited(out, tag, contents);
        return out.toByteArray();
    }

    private static void writeLengthDelimited(final ByteArrayOutputStream out, final int tag, final byte[] contents) {
        writeVarInt(out, tag);
        writeVarInt(out, contents.length);
        out.writeBytes(contents);
    }

    private static void writeVarIntField(final ByteArrayOutputStream out, final int tag, final long value) {
        writeVarInt(out, tag);
        writeVarInt(out, value);
    }

    private static void writeVarInt(final ByteArrayOutputStream out, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            }
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }
}
