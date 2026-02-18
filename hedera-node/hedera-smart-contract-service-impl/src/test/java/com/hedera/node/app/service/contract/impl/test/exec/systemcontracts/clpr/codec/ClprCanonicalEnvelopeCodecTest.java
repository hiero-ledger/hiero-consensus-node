// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.codec;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static org.assertj.core.api.Assertions.assertThat;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprCanonicalEnvelopeCodec;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprPackedInputCodec;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessage.ClprQueueEnqueueMessageTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessageresponse.ClprQueueEnqueueMessageResponseTranslator;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ClprCanonicalEnvelopeCodecTest {
    private static final TupleType<Tuple> REQUEST_ROUTE_HEADER_TYPE =
            TupleType.parse("(uint8,bytes32,address,address)");
    private static final TupleType<Tuple> RESPONSE_ROUTE_HEADER_TYPE = TupleType.parse("(uint8,bytes32,address)");

    @Test
    void decodesCanonicalMessageTuple() {
        final var routeHeader = ClprPackedInputCodec.toArray(REQUEST_ROUTE_HEADER_TYPE.encode(Tuple.of(
                1, new byte[32], asHeadlongAddress(OWNER_BESU_ADDRESS), asHeadlongAddress(OWNER_BESU_ADDRESS))));

        final var amount = Tuple.of(BigInteger.ONE, "tinybar");
        final var applicationMessage =
                Tuple.of(asHeadlongAddress(OWNER_BESU_ADDRESS), new byte[32], amount, new byte[] {0x01});
        final var connectorMessage = Tuple.of(true, amount, new byte[] {0x02});
        final var balanceReport = Tuple.of(new byte[32], amount, amount, amount);
        final var middlewareMessage = Tuple.of(balanceReport, routeHeader);
        final var clprMessage = Tuple.of(
                asHeadlongAddress(OWNER_BESU_ADDRESS),
                applicationMessage,
                new byte[32],
                connectorMessage,
                middlewareMessage);

        final var canonical = ClprPackedInputCodec.stripSelector(ClprPackedInputCodec.toArray(
                ClprQueueEnqueueMessageTranslator.ENQUEUE_MESSAGE.encodeCall(Tuple.singleton(clprMessage))));

        final var decoded = ClprCanonicalEnvelopeCodec.decodeCanonicalMessageTuple(canonical);
        assertThat(decoded).isEqualTo(clprMessage);
    }

    @Test
    void encodesAndDecodesCanonicalResponseTuple() {
        final var routeHeader = ClprPackedInputCodec.toArray(
                RESPONSE_ROUTE_HEADER_TYPE.encode(Tuple.of(1, new byte[32], asHeadlongAddress(OWNER_BESU_ADDRESS))));

        final var amount = Tuple.of(BigInteger.ONE, "tinybar");
        final var balanceReport = Tuple.of(new byte[32], amount, amount, amount);
        final var middlewareMessage = Tuple.of(balanceReport, routeHeader);
        final var middlewareResponse = Tuple.of(0, amount, amount, middlewareMessage);
        final var responseTuple = Tuple.of(
                BigInteger.valueOf(2L),
                Tuple.singleton(new byte[] {0x11}),
                Tuple.singleton(new byte[] {0x22}),
                middlewareResponse);

        final var canonical = ClprCanonicalEnvelopeCodec.encodeCanonicalResponseBytes(responseTuple);
        final var decoded = ClprCanonicalEnvelopeCodec.decodeCanonicalResponseTuple(canonical);

        assertThat(decoded).isEqualTo(responseTuple);

        final var canonicalViaTranslator = ClprPackedInputCodec.stripSelector(ClprPackedInputCodec.toArray(
                ClprQueueEnqueueMessageResponseTranslator.ENQUEUE_MESSAGE_RESPONSE.encodeCall(
                        Tuple.singleton(responseTuple))));
        assertThat(canonical).isEqualTo(canonicalViaTranslator);
    }

    @Test
    void injectsResponseRouteHeader() {
        final var originalRouteHeader = ClprPackedInputCodec.toArray(
                RESPONSE_ROUTE_HEADER_TYPE.encode(Tuple.of(1, new byte[32], asHeadlongAddress(OWNER_BESU_ADDRESS))));
        final var replacementRouteHeader = ClprPackedInputCodec.toArray(RESPONSE_ROUTE_HEADER_TYPE.encode(Tuple.of(
                1,
                new byte[] {
                    0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
                    0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
                    0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
                    0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01
                },
                asHeadlongAddress(OWNER_BESU_ADDRESS))));

        final var amount = Tuple.of(BigInteger.ONE, "tinybar");
        final var balanceReport = Tuple.of(new byte[32], amount, amount, amount);
        final var middlewareMessage = Tuple.of(balanceReport, originalRouteHeader);
        final var middlewareResponse = Tuple.of(0, amount, amount, middlewareMessage);
        final var responseTuple = Tuple.of(
                BigInteger.valueOf(3L),
                Tuple.singleton(new byte[] {0x11}),
                Tuple.singleton(new byte[] {0x22}),
                middlewareResponse);

        final var patched = ClprCanonicalEnvelopeCodec.injectResponseRouteHeader(responseTuple, replacementRouteHeader);
        final var patchedMiddlewareResponse = (Tuple) patched.get(3);
        final var patchedMiddlewareMessage = (Tuple) patchedMiddlewareResponse.get(3);
        assertThat((byte[]) patchedMiddlewareMessage.get(1)).isEqualTo(replacementRouteHeader);
    }
}
