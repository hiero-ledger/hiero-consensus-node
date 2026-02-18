// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Utility for CLPR route-header decode/encode operations. */
public final class ClprRouteHeaderCodec {
    private static final TupleType<Tuple> REQUEST_ROUTE_HEADER_TYPE =
            TupleType.parse("(uint8,bytes32,address,address)");
    private static final TupleType<Tuple> RESPONSE_ROUTE_HEADER_TYPE = TupleType.parse("(uint8,bytes32,address)");

    private ClprRouteHeaderCodec() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static @NonNull RequestRouteHeader decodeRequestHeaderFromMessageTuple(@NonNull final Tuple messageTuple) {
        requireNonNull(messageTuple);
        final var middlewareMessage = (Tuple) messageTuple.get(4);
        final var routeHeaderBytes = (byte[]) middlewareMessage.get(1);
        final var routeHeader = REQUEST_ROUTE_HEADER_TYPE.decode(routeHeaderBytes);
        final var version = ((Number) routeHeader.get(0)).intValue();
        final var remoteLedgerId = (byte[]) routeHeader.get(1);
        final var sourceMiddleware = (Address) routeHeader.get(2);
        final var destinationMiddleware = (Address) routeHeader.get(3);
        return new RequestRouteHeader(version, remoteLedgerId, sourceMiddleware, destinationMiddleware);
    }

    public static @NonNull ResponseRouteHeader decodeResponseHeaderFromResponseTuple(
            @NonNull final Tuple responseTuple) {
        requireNonNull(responseTuple);
        final var middlewareResponse = (Tuple) responseTuple.get(3);
        final var middlewareMessage = (Tuple) middlewareResponse.get(3);
        final var routeHeaderBytes = (byte[]) middlewareMessage.get(1);
        final var routeHeader = RESPONSE_ROUTE_HEADER_TYPE.decode(routeHeaderBytes);
        final var version = ((Number) routeHeader.get(0)).intValue();
        final var remoteLedgerId = (byte[]) routeHeader.get(1);
        final var targetMiddleware = (Address) routeHeader.get(2);
        return new ResponseRouteHeader(version, remoteLedgerId, targetMiddleware);
    }

    public static @NonNull byte[] encodeResponseRouteHeader(
            final int version, @NonNull final byte[] remoteLedgerId, @NonNull final Address targetMiddleware) {
        requireNonNull(remoteLedgerId);
        requireNonNull(targetMiddleware);
        return ClprPackedInputCodec.toArray(
                RESPONSE_ROUTE_HEADER_TYPE.encode(Tuple.of(version, remoteLedgerId, targetMiddleware)));
    }

    public record RequestRouteHeader(
            int version, byte[] remoteLedgerId, Address sourceMiddleware, Address destinationMiddleware) {}

    public record ResponseRouteHeader(int version, byte[] remoteLedgerId, Address targetMiddleware) {}
}
