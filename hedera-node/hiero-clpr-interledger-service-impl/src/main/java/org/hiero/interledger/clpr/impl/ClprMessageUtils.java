// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static org.hiero.interledger.clpr.ClprStateProofUtils.buildLocalClprStateProofWrapper;

import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.function.Function;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageBundle;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;

/**
 * Utility methods for working with CLPR messages.
 *
 * <p>This class provides helper functions for creating message bundles
 * and calculating running hashes for CLPR message payloads. It is designed to encapsulate
 * common operations related to CLPR message processing.</p>
 */
public class ClprMessageUtils {

    /**
     * Creates a CLPR message bundle from a range of messages.
     *
     * <p>This method constructs a bundle containing a specified range of messages
     * and a state proof derived from the last message in the range. It is used to
     * group and package messages for transfer.</p>
     *
     * @param firstPendingMsgId The ID of the first message to include in the bundle.
     * @param lastMsgInBundle The ID of the last message to include in the bundle. This message's state proof
     *                        is used for the bundle's state proof.
     * @param localLedgerId The ID of the local ledger.
     * @param remoteLedgerId The ID of the bundle destination ledger.
     * @param getMessageFunction A function that retrieves a {@link ClprMessageValue} given a {@link ClprMessageKey}.
     * @return A {@link ClprMessageBundle} containing the specified messages and a state proof,
     *         or {@code null} if any required message or state proof cannot be retrieved.
     */
    @Nullable
    public static ClprMessageBundle createBundle(
            long firstPendingMsgId,
            long lastMsgInBundle,
            @NonNull ClprLedgerId localLedgerId,
            @NonNull ClprLedgerId remoteLedgerId,
            @NonNull Function<ClprMessageKey, ClprMessageValue> getMessageFunction) {
        final var messagePayloadList = new ArrayList<ClprMessagePayload>();
        final var bundleBuilder = ClprMessageBundle.newBuilder();
        for (long i = firstPendingMsgId; i < lastMsgInBundle; i++) {
            final var messageKey = ClprMessageKey.newBuilder()
                    .messageId(i)
                    .ledgerId(remoteLedgerId)
                    .build();
            final var messageValue = getMessageFunction.apply(messageKey);
            // If there is no value in the given range there should be a problem at the calculated range
            if (messageValue == null) {
                return null;
            }

            if (messageValue.payloadOrThrow().hasMessage()) {
                final var payload = ClprMessagePayload.newBuilder()
                        .message(messageValue.payloadOrThrow().message())
                        .build();
                messagePayloadList.add(payload);
            } else {
                final var payload = ClprMessagePayload.newBuilder()
                        .messageReply(messageValue.payloadOrThrow().messageReply())
                        .build();
                messagePayloadList.add(payload);
            }
        }

        // construct msg bundle state proof
        final var lastMessageKey = ClprMessageKey.newBuilder()
                .messageId(lastMsgInBundle)
                .ledgerId(remoteLedgerId)
                .build();
        final var lastMessageValue = getMessageFunction.apply(lastMessageKey);
        if (lastMessageValue == null) {
            return null;
        }

        final var bundleStateProof = buildLocalClprStateProofWrapper(lastMessageKey, lastMessageValue);
        bundleBuilder.ledgerId(localLedgerId).stateProof(bundleStateProof);

        // add payloads if any
        if (!messagePayloadList.isEmpty()) {
            bundleBuilder.messages(messagePayloadList);
        }

        return bundleBuilder.build();
    }

    /**
     * @param byteArray the byte array to hash
     * @return the byte array of the hashed value
     */
    public static byte[] noThrowSha384HashOf(final byte[] byteArray) {
        try {
            return MessageDigest.getInstance("SHA-384").digest(byteArray);
        } catch (final NoSuchAlgorithmException fatal) {
            throw new IllegalStateException(fatal);
        }
    }

    /**
     * Calculates the next running hash based on a message payload and the previous hash.
     *
     * <p>This method combines the previous running hash and the current message payload,
     * then computes a SHA-384 hash of the combined data to produce the new running hash.
     * This is typically used to maintain an aggregate hash of a sequence of messages.</p>
     *
     * @param payload The current message payload.
     * @param previousHash The previous running hash (as {@link Bytes}).
     * @return The new running hash as {@link Bytes}.
     * @throws RuntimeException if an I/O error occurs during hashing.
     */
    public static Bytes nextRunningHash(ClprMessagePayload payload, Bytes previousHash) {
        final var payloadByteArray = CommonPbjConverters.asBytes(ClprMessagePayload.PROTOBUF.toBytes(payload));
        final var boas = new ByteArrayOutputStream();
        try (final var out = new ObjectOutputStream(boas)) {
            out.writeObject(CommonPbjConverters.asBytes(previousHash));
            out.writeObject(payloadByteArray);
            out.flush();
            return Bytes.wrap(noThrowSha384HashOf(boas.toByteArray()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
