// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessage.ClprQueueEnqueueMessageTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessageresponse.ClprQueueEnqueueMessageResponseTranslator;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Utility for canonical CLPR envelope tuple decode/encode and route-header patching. */
public final class ClprCanonicalEnvelopeCodec {
    private ClprCanonicalEnvelopeCodec() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static @NonNull Tuple decodeCanonicalMessageTuple(@NonNull final byte[] canonicalMessageData) {
        requireNonNull(canonicalMessageData);
        final var enqueueCallData = ClprPackedInputCodec.prependSelector(
                ClprQueueEnqueueMessageTranslator.ENQUEUE_MESSAGE.selector(), canonicalMessageData);
        final var enqueueArgs = ClprQueueEnqueueMessageTranslator.ENQUEUE_MESSAGE.decodeCall(enqueueCallData);
        return (Tuple) enqueueArgs.get(0);
    }

    public static @NonNull Tuple decodeCanonicalResponseTuple(@NonNull final byte[] canonicalResponseData) {
        requireNonNull(canonicalResponseData);
        final var enqueueResponseCallData = ClprPackedInputCodec.prependSelector(
                ClprQueueEnqueueMessageResponseTranslator.ENQUEUE_MESSAGE_RESPONSE.selector(), canonicalResponseData);
        final var enqueueResponseArgs =
                ClprQueueEnqueueMessageResponseTranslator.ENQUEUE_MESSAGE_RESPONSE.decodeCall(enqueueResponseCallData);
        return (Tuple) enqueueResponseArgs.get(0);
    }

    public static @NonNull Tuple injectResponseRouteHeader(
            @NonNull final Tuple responseTuple, @NonNull final byte[] routeHeaderBytes) {
        requireNonNull(responseTuple);
        requireNonNull(routeHeaderBytes);
        final var middlewareResponse = (Tuple) responseTuple.get(3);
        final var middlewareMessage = (Tuple) middlewareResponse.get(3);
        final var patchedMiddlewareMessage = Tuple.of(middlewareMessage.get(0), routeHeaderBytes);
        final var patchedMiddlewareResponse = Tuple.of(
                middlewareResponse.get(0),
                middlewareResponse.get(1),
                middlewareResponse.get(2),
                patchedMiddlewareMessage);
        return Tuple.of(responseTuple.get(0), responseTuple.get(1), responseTuple.get(2), patchedMiddlewareResponse);
    }

    public static @NonNull byte[] encodeCanonicalResponseBytes(@NonNull final Tuple responseTuple) {
        requireNonNull(responseTuple);
        final var enqueueResponseCallData = ClprPackedInputCodec.toArray(
                ClprQueueEnqueueMessageResponseTranslator.ENQUEUE_MESSAGE_RESPONSE.encodeCall(
                        Tuple.singleton(responseTuple)));
        return ClprPackedInputCodec.stripSelector(enqueueResponseCallData);
    }
}
