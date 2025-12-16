// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.BufferUnderflowException;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Performs lightweight pre-validation of transaction fields by scanning raw protobuf bytes
 * without full parsing. This allows early rejection of invalid transactions (e.g., oversized memo)
 * before investing CPU cycles and GC pressure in full object graph construction.
 *
 * <p>This class is optimized to avoid allocating any intermediate {@link Bytes} objects during
 * navigation. It scans through the nested protobuf structure in a single pass:
 * <ul>
 *   <li>Transaction: field 5 (signedTransactionBytes) or field 4 (bodyBytes, deprecated)</li>
 *   <li>SignedTransaction: field 1 (bodyBytes)</li>
 *   <li>TransactionBody: field 6 (memo)</li>
 * </ul>
 */
@Singleton
public class TransactionPreCheck {

    // Field numbers from transaction.proto (Transaction)
    private static final int TRANSACTION_BODY_BYTES_FIELD = 4; // deprecated
    private static final int TRANSACTION_SIGNED_TX_BYTES_FIELD = 5;

    // Field numbers from transaction_contents.proto (SignedTransaction)
    private static final int SIGNED_TX_BODY_BYTES_FIELD = 1;

    // Field numbers from transaction.proto (TransactionBody)
    private static final int TX_BODY_MEMO_FIELD = 6;

    private final ConfigProvider configProvider;

    @Inject
    public TransactionPreCheck(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
    }

    /**
     * Performs early validation checks on the raw transaction bytes without full parsing.
     * Currently validates:
     * <ul>
     *   <li>Memo size does not exceed the configured maximum</li>
     *   <li>Memo does not contain zero bytes</li>
     * </ul>
     *
     * <p>This method navigates through the nested protobuf structure without allocating
     * any intermediate {@link Bytes} objects, minimizing GC pressure.
     *
     * @param txBytes the raw transaction bytes
     * @throws PreCheckException if any early validation check fails
     */
    public void validateEarlyChecks(@NonNull final Bytes txBytes) throws PreCheckException {
        requireNonNull(txBytes, "txBytes must not be null");

        final var hederaConfig = configProvider.getConfiguration().getConfigData(HederaConfig.class);
        final int maxMemoUtf8Bytes = hederaConfig.transactionMaxMemoUtf8Bytes();

        try {
            final var input = txBytes.toReadableSequentialData();
            scanTransactionForMemo(input, maxMemoUtf8Bytes);
        } catch (final PreCheckException e) {
            throw e;
        } catch (final Exception e) {
            // If we can't parse the structure, let the full parser handle it
            // This includes malformed protobufs, unexpected wire types, etc.
        }
    }

    /**
     * Scans a Transaction message looking for the memo field nested within.
     * Navigates through Transaction -> SignedTransaction/bodyBytes -> TransactionBody -> memo
     * without allocating intermediate Bytes objects.
     *
     * @param input the input stream positioned at the start of a Transaction message
     * @param maxMemoUtf8Bytes the maximum allowed memo size
     * @throws PreCheckException if the memo validation fails
     */
    private void scanTransactionForMemo(
            @NonNull final ReadableSequentialData input, final int maxMemoUtf8Bytes) throws PreCheckException {
        while (input.hasRemaining()) {
            final int tag = readTag(input);
            if (tag == -1) break;

            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            final int wireType = tag & ProtoConstants.TAG_WIRE_TYPE_MASK;

            if (wireType == ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                final int length = input.readVarInt(false);

                if (fieldNum == TRANSACTION_SIGNED_TX_BYTES_FIELD) {
                    // Found signedTransactionBytes - scan into SignedTransaction for bodyBytes
                    scanSignedTransactionForMemo(input, length, maxMemoUtf8Bytes);
                    return;
                } else if (fieldNum == TRANSACTION_BODY_BYTES_FIELD) {
                    // Found deprecated bodyBytes - this IS the TransactionBody, scan for memo
                    scanTransactionBodyForMemo(input, length, maxMemoUtf8Bytes);
                    return;
                } else {
                    // Skip this length-delimited field
                    input.skip(length);
                }
            } else {
                // Skip non-length-delimited fields
                skipFieldByWireType(input, wireType);
            }
        }
    }

    /**
     * Scans a SignedTransaction message looking for bodyBytes field.
     *
     * @param input the input stream positioned at the start of SignedTransaction content
     * @param bytesAvailable the number of bytes in this SignedTransaction message
     * @param maxMemoUtf8Bytes the maximum allowed memo size
     * @throws PreCheckException if the memo validation fails
     */
    private void scanSignedTransactionForMemo(
            @NonNull final ReadableSequentialData input, final int bytesAvailable, final int maxMemoUtf8Bytes)
            throws PreCheckException {
        final long endPosition = input.position() + bytesAvailable;

        while (input.position() < endPosition && input.hasRemaining()) {
            final int tag = readTag(input);
            if (tag == -1) break;

            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            final int wireType = tag & ProtoConstants.TAG_WIRE_TYPE_MASK;

            if (wireType == ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                final int length = input.readVarInt(false);

                if (fieldNum == SIGNED_TX_BODY_BYTES_FIELD) {
                    // Found bodyBytes - this IS the TransactionBody, scan for memo
                    scanTransactionBodyForMemo(input, length, maxMemoUtf8Bytes);
                    return;
                } else {
                    // Skip this length-delimited field
                    input.skip(length);
                }
            } else {
                skipFieldByWireType(input, wireType);
            }
        }
    }

    /**
     * Scans a TransactionBody message looking for the memo field.
     *
     * @param input the input stream positioned at the start of TransactionBody content
     * @param bytesAvailable the number of bytes in this TransactionBody message
     * @param maxMemoUtf8Bytes the maximum allowed memo size
     * @throws PreCheckException if the memo is too long or contains zero bytes
     */
    private void scanTransactionBodyForMemo(
            @NonNull final ReadableSequentialData input, final int bytesAvailable, final int maxMemoUtf8Bytes)
            throws PreCheckException {
        final long endPosition = input.position() + bytesAvailable;

        while (input.position() < endPosition && input.hasRemaining()) {
            final int tag = readTag(input);
            if (tag == -1) break;

            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            final int wireType = tag & ProtoConstants.TAG_WIRE_TYPE_MASK;

            if (fieldNum == TX_BODY_MEMO_FIELD) {
                if (wireType != ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                    return; // Unexpected wire type, let full parser handle
                }

                // Read just the length - this is the key optimization
                final int length = input.readVarInt(false);

                // Fail fast if memo is too long, without reading the actual bytes
                if (length > maxMemoUtf8Bytes) {
                    throw new PreCheckException(MEMO_TOO_LONG);
                }

                // Only scan bytes for zero-byte check if length is valid
                for (int i = 0; i < length; i++) {
                    if (input.readByte() == 0) {
                        throw new PreCheckException(INVALID_ZERO_BYTE_IN_STRING);
                    }
                }
                return; // Found and validated memo, done
            } else if (wireType == ProtoConstants.WIRE_TYPE_DELIMITED.ordinal()) {
                final int length = input.readVarInt(false);
                input.skip(length);
            } else {
                skipFieldByWireType(input, wireType);
            }
        }
        // No memo field found - that's fine, memo is optional
    }

    /**
     * Reads a protobuf tag from the input, returning -1 on EOF.
     */
    private int readTag(@NonNull final ReadableSequentialData input) {
        try {
            return input.readVarInt(false);
        } catch (final BufferUnderflowException e) {
            return -1;
        }
    }

    /**
     * Skips a field based on its wire type (for non-length-delimited types).
     * Length-delimited fields should be handled separately after reading their length.
     */
    private void skipFieldByWireType(@NonNull final ReadableSequentialData input, final int wireType) {
        switch (wireType) {
            case 0 -> input.readVarLong(false); // VARINT
            case 1 -> input.skip(8); // FIXED64
            case 5 -> input.skip(4); // FIXED32
            default -> {} // Unknown wire type or WIRE_TYPE_DELIMITED (handled separately)
        }
    }
}
