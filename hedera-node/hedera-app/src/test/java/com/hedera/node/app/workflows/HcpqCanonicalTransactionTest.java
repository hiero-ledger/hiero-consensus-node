// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_HAS_UNKNOWN_FIELDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class HcpqCanonicalTransactionTest extends AppTestBase {
    /**
     * An identical copy of {@code canonical-transaction-body-vectors.json} from the HCPQ reference implementation in
     * hiero-cryptography ({@code cryptography/hedera-cryptography-hcpq/src/test/resources/hcpq-v1}).
     */
    private static final String VECTORS = "/hcpq-v1/canonical-transaction-body-vectors.json";

    private static final int MAX_BYTES = 6144;

    private static final SignaturePair HCPQ_SIGNATURE = SignaturePair.newBuilder()
            .pubKeyPrefix(Bytes.wrap(new byte[32]))
            .mlDsa44(Bytes.wrap(new byte[2420]))
            .build();

    private static final SignaturePair ED25519_SIGNATURE = SignaturePair.newBuilder()
            .pubKeyPrefix(Bytes.EMPTY)
            .ed25519(Bytes.wrap(new byte[64]))
            .build();

    private record Vector(
            String id, String result, String rejectedBy, String equivalentTo, Bytes body, JsonNode bodyJson) {}

    @Test
    void requiresExactPbjEncodingOnlyForHcpq() {
        final var body = TransactionBody.DEFAULT;
        final var canonical = TransactionBody.PROTOBUF.toBytes(body);
        final var canonicalSigned = new SignedTransaction(
                canonical, SignatureMap.newBuilder().sigPair(HCPQ_SIGNATURE).build(), false);
        assertThat(TransactionChecker.hasCanonicalHcpqBody(canonicalSigned, body))
                .isTrue();

        final var alternate = Arrays.copyOf(canonical.toByteArray(), Math.toIntExact(canonical.length()) + 2);
        // An explicitly encoded empty memo is semantically the default but not canonical PBJ output.
        alternate[alternate.length - 2] = 0x32;
        alternate[alternate.length - 1] = 0x00;
        final var alternateHcpq =
                canonicalSigned.copyBuilder().bodyBytes(Bytes.wrap(alternate)).build();
        assertThat(TransactionChecker.hasCanonicalHcpqBody(alternateHcpq, body)).isFalse();

        final var classical = alternateHcpq
                .copyBuilder()
                .sigMap(SignatureMap.newBuilder().sigPair(ED25519_SIGNATURE).build())
                .build();
        assertThat(TransactionChecker.hasCanonicalHcpqBody(classical, body)).isTrue();
    }

    /**
     * Runs every published canonical-body vector through the ingest checker with HCPQ enabled. Accepted bodies must pass
     * every check and match their proto3 JSON form and the protobuf-java encoding. Rejected bodies must fail with the
     * documented stage: alternate encodings parse to their canonical counterpart and are only rejected because an HCPQ
     * signature is present, while malformed bodies are rejected whatever the signature type.
     */
    @TestFactory
    Stream<DynamicTest> canonicalTransactionBodyVectors() throws IOException {
        final var checker = hcpqEnabledChecker();
        final var vectors = loadVectors();
        final Map<String, TransactionBody> canonicalBodies = vectors.stream()
                .filter(v -> v.result().equals("accept"))
                .collect(Collectors.toMap(Vector::id, v -> parse(v.body())));

        return vectors.stream()
                .map(v -> dynamicTest(v.id(), () -> {
                    switch (v.result()) {
                        case "accept" -> assertAccepted(checker, v);
                        case "reject" -> assertRejected(checker, v, canonicalBodies);
                        default -> throw new IllegalArgumentException(v.id() + ": result must be accept or reject");
                    }
                }));
    }

    private static void assertAccepted(final TransactionChecker checker, final Vector v) throws Exception {
        final var info = check(checker, v.body(), HCPQ_SIGNATURE);
        assertThat(TransactionBody.PROTOBUF.toBytes(info.txBody())).isEqualTo(v.body());

        final var fromJson =
                TransactionBody.JSON.parse(Bytes.wrap(v.bodyJson().toString().getBytes(StandardCharsets.UTF_8)));
        assertThat(TransactionBody.PROTOBUF.toBytes(fromJson))
                .as("body_json encodes to body")
                .isEqualTo(v.body());

        final var protobufJava = com.hederahashgraph.api.proto.java.TransactionBody.parseFrom(
                v.body().toByteArray());
        assertThat(protobufJava.toByteArray())
                .as("protobuf-java re-encodes body unchanged")
                .isEqualTo(v.body().toByteArray());
    }

    private static void assertRejected(
            final TransactionChecker checker, final Vector v, final Map<String, TransactionBody> canonicalBodies) {
        final var hcpq = catchThrowableOfType(PreCheckException.class, () -> check(checker, v.body(), HCPQ_SIGNATURE));
        assertThat(hcpq).as("HCPQ-signed body is rejected").isNotNull();

        switch (v.rejectedBy()) {
            case "canonical_reencode" -> {
                assertThat(hcpq.responseCode()).isEqualTo(INVALID_TRANSACTION_BODY);
                assertThat(parse(v.body()))
                        .as("parses to the same body as " + v.equivalentTo())
                        .isEqualTo(canonicalBodies.get(v.equivalentTo()));
                assertThatNoException()
                        .as("the same bytes are accepted without an HCPQ signature")
                        .isThrownBy(() -> check(checker, v.body(), ED25519_SIGNATURE));
            }
            case "strict_parse" -> {
                assertThat(hcpq.responseCode()).isIn(INVALID_TRANSACTION_BODY, TRANSACTION_HAS_UNKNOWN_FIELDS);
                final var classical = catchThrowableOfType(
                        PreCheckException.class, () -> check(checker, v.body(), ED25519_SIGNATURE));
                assertThat(classical)
                        .as("malformed bodies are rejected without an HCPQ signature too")
                        .isNotNull();
                assertThat(classical.responseCode()).isEqualTo(hcpq.responseCode());
            }
            default -> throw new IllegalArgumentException(v.id() + ": unknown rejected_by " + v.rejectedBy());
        }
    }

    private static TransactionInfo check(final TransactionChecker checker, final Bytes body, final SignaturePair pair)
            throws PreCheckException {
        final var signedTx = new SignedTransaction(
                body, SignatureMap.newBuilder().sigPair(pair).build(), false);
        return checker.checkSigned(signedTx, SignedTransaction.PROTOBUF.toBytes(signedTx), MAX_BYTES);
    }

    private static TransactionBody parse(final Bytes body) {
        try {
            return TransactionBody.PROTOBUF.parse(body);
        } catch (Exception e) {
            throw new IllegalStateException("canonical vector body must parse", e);
        }
    }

    private TransactionChecker hcpqEnabledChecker() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("hcpq.enabled", true)
                .withValue("hcpq.maxSignaturesPerTransaction", 1)
                .getOrCreateConfig();
        return new TransactionChecker(() -> new VersionedConfigImpl(config, 1), metrics);
    }

    private static List<Vector> loadVectors() throws IOException {
        try (var in = HcpqCanonicalTransactionTest.class.getResourceAsStream(VECTORS)) {
            assertThat(in).as(VECTORS).isNotNull();
            final var root = new ObjectMapper().readTree(in);
            final var hex = HexFormat.of();
            return StreamSupport.stream(root.get("tests").spliterator(), false)
                    .map(t -> new Vector(
                            t.get("id").asText(),
                            t.get("result").asText(),
                            t.path("rejected_by").asText(null),
                            t.path("equivalent_to").asText(null),
                            Bytes.wrap(hex.parseHex(t.get("body").asText())),
                            t.get("body_json")))
                    .toList();
        }
    }
}
