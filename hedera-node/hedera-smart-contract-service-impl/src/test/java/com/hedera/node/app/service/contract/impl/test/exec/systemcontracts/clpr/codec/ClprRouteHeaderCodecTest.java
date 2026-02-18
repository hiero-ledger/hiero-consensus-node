// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.codec;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprPackedInputCodec;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprRouteHeaderCodec;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ClprRouteHeaderCodecTest {
    private static final TupleType<Tuple> REQUEST_ROUTE_HEADER_TYPE =
            TupleType.parse("(uint8,bytes32,address,address)");
    private static final TupleType<Tuple> RESPONSE_ROUTE_HEADER_TYPE = TupleType.parse("(uint8,bytes32,address)");

    @Test
    void decodesRequestRouteHeaderFromMessageTuple() {
        final var remoteLedgerId = new byte[32];
        remoteLedgerId[31] = 0x2A;

        final var routeHeaderBytes = ClprPackedInputCodec.toArray(REQUEST_ROUTE_HEADER_TYPE.encode(Tuple.of(
                1, remoteLedgerId, asHeadlongAddress(OWNER_BESU_ADDRESS), asHeadlongAddress(OWNER_BESU_ADDRESS))));

        final var amount = Tuple.of(BigInteger.ONE, "tinybar");
        final var applicationMessage =
                Tuple.of(asHeadlongAddress(OWNER_BESU_ADDRESS), new byte[32], amount, new byte[] {0x01});
        final var connectorMessage = Tuple.of(true, amount, new byte[] {0x02});
        final var balanceReport = Tuple.of(new byte[32], amount, amount, amount);
        final var middlewareMessage = Tuple.of(balanceReport, routeHeaderBytes);
        final var messageTuple = Tuple.of(
                asHeadlongAddress(OWNER_BESU_ADDRESS),
                applicationMessage,
                new byte[32],
                connectorMessage,
                middlewareMessage);

        final var decoded = ClprRouteHeaderCodec.decodeRequestHeaderFromMessageTuple(messageTuple);
        assertThat(decoded.version()).isEqualTo(1);
        assertThat(decoded.remoteLedgerId()).isEqualTo(remoteLedgerId);
    }

    @Test
    void decodesResponseRouteHeaderFromResponseTuple() {
        final var remoteLedgerId = new byte[32];
        remoteLedgerId[0] = 0x07;

        final var routeHeaderBytes = ClprPackedInputCodec.toArray(
                RESPONSE_ROUTE_HEADER_TYPE.encode(Tuple.of(1, remoteLedgerId, asHeadlongAddress(OWNER_BESU_ADDRESS))));

        final var amount = Tuple.of(BigInteger.ONE, "tinybar");
        final var balanceReport = Tuple.of(new byte[32], amount, amount, amount);
        final var middlewareMessage = Tuple.of(balanceReport, routeHeaderBytes);
        final var middlewareResponse = Tuple.of(0, amount, amount, middlewareMessage);
        final var responseTuple = Tuple.of(
                BigInteger.ONE,
                Tuple.singleton(new byte[] {0x11}),
                Tuple.singleton(new byte[] {0x22}),
                middlewareResponse);

        final var decoded = ClprRouteHeaderCodec.decodeResponseHeaderFromResponseTuple(responseTuple);
        assertThat(decoded.version()).isEqualTo(1);
        assertThat(decoded.remoteLedgerId()).isEqualTo(remoteLedgerId);
    }

    @Test
    void rejectsMalformedRequestRouteHeaderBytes() {
        final var amount = Tuple.of(BigInteger.ONE, "tinybar");
        final var applicationMessage =
                Tuple.of(asHeadlongAddress(OWNER_BESU_ADDRESS), new byte[32], amount, new byte[] {0x01});
        final var connectorMessage = Tuple.of(true, amount, new byte[] {0x02});
        final var balanceReport = Tuple.of(new byte[32], amount, amount, amount);
        final var middlewareMessage = Tuple.of(balanceReport, new byte[] {0x01});
        final var messageTuple = Tuple.of(
                asHeadlongAddress(OWNER_BESU_ADDRESS),
                applicationMessage,
                new byte[32],
                connectorMessage,
                middlewareMessage);

        assertThatThrownBy(() -> ClprRouteHeaderCodec.decodeRequestHeaderFromMessageTuple(messageTuple))
                .isInstanceOf(RuntimeException.class);
    }
}
