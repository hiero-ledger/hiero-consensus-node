// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.queries;

import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_QUERY_HEADER;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hederahashgraph.api.proto.java.CryptoGetInfoQuery;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Transaction;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Demonstrates end-to-end that the embedded node resolves a duplicated non-repeated field in a paid query the
 * same way as the canonical parse used for the rest of the query workflow.
 *
 * <p>A paid query (here {@code CryptoGetInfo} with {@code ANSWER_ONLY}) carries its payment in
 * {@code QueryHeader.payment}. The workflow lifts the payment out with a hand-written walker
 * ({@code ProtobufUtils}) to verify and submit it, while it parses the whole query with PBJ, which keeps the
 * <b>last</b> occurrence of a duplicated field. If the walker kept the <b>first</b> occurrence instead, a query
 * that encodes the payment twice would have its first payment verified and submitted while the rest of the node
 * acted on the last — the two would disagree on the payment. The walker now rejects any such duplicate, so a
 * duplicate-payment query is refused with the {@code INVALID_QUERY_HEADER} status.
 *
 * <p>This runs only in embedded mode because a duplicate non-repeated field cannot be produced through the
 * normal gRPC/builder send path — protobuf always serializes a canonical, single-occurrence encoding — so the
 * test hands the node raw crafted bytes directly through the in-process query workflow.
 */
public class DuplicateQueryPaymentFieldTest {

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> nodeRejectsPaidQueryWithDuplicatePaymentField() {
        return hapiTest(doingContextual(spec -> {
            final var paymentA = Transaction.newBuilder()
                    .setSignedTransactionBytes(ByteString.copyFromUtf8("payment-A"))
                    .build();
            final var paymentB = Transaction.newBuilder()
                    .setSignedTransactionBytes(ByteString.copyFromUtf8("payment-B"))
                    .build();

            // Control: a well-formed paid query with a SINGLE payment. It gets past the payment walker and fails
            // later during payment verification (the payment is deliberately not a valid signed transfer), i.e.
            // with some status other than INVALID_QUERY_HEADER.
            final var singlePayment = Query.newBuilder()
                    .setCryptoGetInfo(CryptoGetInfoQuery.newBuilder()
                            .setHeader(QueryHeader.newBuilder()
                                    .setResponseType(ResponseType.ANSWER_ONLY)
                                    .setPayment(paymentA)))
                    .build()
                    .toByteArray();

            // Duplicate: a QueryHeader whose payment field appears twice (A then B). Built by concatenating a
            // header carrying (responseType, payment=A) with a header carrying (payment=B); protobuf treats the
            // concatenation as one QueryHeader whose payment field is present twice, and PBJ keeps payment=B.
            final var headerWithA = QueryHeader.newBuilder()
                    .setResponseType(ResponseType.ANSWER_ONLY)
                    .setPayment(paymentA)
                    .build()
                    .toByteArray();
            final var headerWithB =
                    QueryHeader.newBuilder().setPayment(paymentB).build().toByteArray();
            final var duplicatePaymentHeader = concat(headerWithA, headerWithB);
            final var duplicatePayment = lenField(
                    Query.CRYPTOGETINFO_FIELD_NUMBER,
                    lenField(CryptoGetInfoQuery.HEADER_FIELD_NUMBER, duplicatePaymentHeader));

            final var embedded = spec.embeddedHederaOrThrow();
            final var singleCode = embedded.sendQueryRaw(singlePayment)
                    .getCryptoGetInfo()
                    .getHeader()
                    .getNodeTransactionPrecheckCode();
            final var duplicateCode = embedded.sendQueryRaw(duplicatePayment)
                    .getCryptoGetInfo()
                    .getHeader()
                    .getNodeTransactionPrecheckCode();

            // The duplicate-payment query is refused by the payment walker before any verification.
            assertEquals(INVALID_QUERY_HEADER, duplicateCode);
            // The single-payment query is not refused by the walker — it reaches payment verification and fails
            // there — which confirms INVALID_QUERY_HEADER above is specifically the duplicate being rejected.
            assertNotEquals(INVALID_QUERY_HEADER, singleCode);
        }));
    }

    /**
     * The positive counterpart of the test above: the FIRST of the two payment occurrences is a genuinely valid,
     * signed payment (captured from a real successful paid query), so without the fix the node would extract and
     * verify that valid payment and answer the query — i.e. the checks would pass on an ambiguous-payment query
     * whose canonical (PBJ, last-wins) reading names a different payment. With the fix the duplicate is refused
     * with {@code INVALID_QUERY_HEADER} before any verification.
     */
    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    final Stream<DynamicTest> nodeRejectsPaidQueryWhoseValidFirstPaymentIsDuplicated() {
        final AtomicReference<Query> validQuery = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("target"),
                // Let the framework build a real, signed, funded ANSWER_ONLY payment; capture it. We then strip the
                // payment from the query the framework actually sends so its transaction id is never submitted —
                // otherwise reusing it in our raw send below would trip ingest de-duplication. The framework send
                // therefore fails with INVALID_QUERY_HEADER (payment required but absent), which we expect.
                getAccountInfo("target")
                        .payingWith("payer")
                        .hasAnswerOnlyPrecheck(INVALID_QUERY_HEADER)
                        .withQueryMutation((query, spec) -> {
                            if (!query.hasCryptoGetInfo()
                                    || query.getCryptoGetInfo().getHeader().getResponseType()
                                            != ResponseType.ANSWER_ONLY) {
                                return query;
                            }
                            validQuery.set(query);
                            return query.toBuilder()
                                    .setCryptoGetInfo(query.getCryptoGetInfo().toBuilder()
                                            .setHeader(query.getCryptoGetInfo().getHeader().toBuilder()
                                                    .clearPayment()))
                                    .build();
                        }),
                doingContextual(spec -> {
                    final var valid = requireNonNull(validQuery.get(), "did not capture a paid ANSWER_ONLY query");
                    final var cryptoGetInfo = valid.getCryptoGetInfo();
                    final var validPayment = cryptoGetInfo.getHeader().getPayment();
                    // A second, different payment appended after the valid one, so first != last.
                    final var shadowPayment = Transaction.newBuilder()
                            .setSignedTransactionBytes(ByteString.copyFromUtf8("shadow-payment"))
                            .build();

                    // A QueryHeader whose payment field appears twice: the valid payment first, the shadow last.
                    final var headerWithValid = QueryHeader.newBuilder()
                            .setResponseType(ResponseType.ANSWER_ONLY)
                            .setPayment(validPayment)
                            .build()
                            .toByteArray();
                    final var headerWithShadow = QueryHeader.newBuilder()
                            .setPayment(shadowPayment)
                            .build()
                            .toByteArray();
                    final var duplicatePaymentHeader = concat(headerWithValid, headerWithShadow);

                    // Rebuild the CryptoGetInfoQuery preserving the queried account id, so that without the fix the
                    // query is fully answerable once its (valid, first) payment clears.
                    final var accountIdField = CryptoGetInfoQuery.newBuilder()
                            .setAccountID(cryptoGetInfo.getAccountID())
                            .build()
                            .toByteArray();
                    final var cryptoGetInfoBytes = concat(
                            lenField(CryptoGetInfoQuery.HEADER_FIELD_NUMBER, duplicatePaymentHeader), accountIdField);
                    final var duplicatePayment = lenField(Query.CRYPTOGETINFO_FIELD_NUMBER, cryptoGetInfoBytes);

                    final var code = spec.embeddedHederaOrThrow()
                            .sendQueryRaw(duplicatePayment)
                            .getCryptoGetInfo()
                            .getHeader()
                            .getNodeTransactionPrecheckCode();
                    assertEquals(INVALID_QUERY_HEADER, code);
                }));
    }

    /** Encode a single length-delimited protobuf field: tag (fieldNumber, wire type 2) + length + value. */
    private static byte[] lenField(final int fieldNumber, final byte[] value) {
        final var out = new ByteArrayOutputStream();
        writeVarint(out, ((long) fieldNumber << 3) | 2);
        writeVarint(out, value.length);
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final var out = new ByteArrayOutputStream();
        out.writeBytes(a);
        out.writeBytes(b);
        return out.toByteArray();
    }

    private static void writeVarint(final ByteArrayOutputStream out, long value) {
        while (true) {
            final int b = (int) (value & 0x7F);
            value >>>= 7;
            if (value != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return;
            }
        }
    }
}
