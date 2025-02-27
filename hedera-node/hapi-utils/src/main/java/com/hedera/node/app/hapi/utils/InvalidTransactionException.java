// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import org.apache.tuweni.bytes.Bytes;

/**
 * Captures a failure in transaction processing to be captured by the {TransitionRunner} and used to
 * set the final resolved status of the transaction.
 *
 * <p>Unless the contained {@link ResponseCodeEnum} is exactly {@code FAIL_INVALID}, this represents
 * some form of user error. The {@code FAIL_INVALID} code indicates an internal system error; and it
 * is usually desirable in that case to include a detail message in the constructor.
 */
public class InvalidTransactionException extends RuntimeException {

    private final ResponseCodeEnum responseCode;
    private final boolean reverting;

    public InvalidTransactionException(final ResponseCodeEnum responseCode) {
        this(responseCode.name(), responseCode, false);
    }

    public InvalidTransactionException(final ResponseCodeEnum responseCode, boolean reverting) {
        this(responseCode.name(), responseCode, reverting);
    }

    public InvalidTransactionException(final String detailMessage, final ResponseCodeEnum responseCode) {
        this(detailMessage, responseCode, false);
    }

    public InvalidTransactionException(
            final String detailMessage, final ResponseCodeEnum responseCode, boolean reverting) {
        super(detailMessage);
        this.responseCode = responseCode;
        this.reverting = reverting;
    }

    public ResponseCodeEnum getResponseCode() {
        return responseCode;
    }

    public boolean isReverting() {
        return reverting;
    }

    public Bytes getRevertReason() {
        if (!isReverting()) {
            throw new IllegalStateException();
        }
        return messageBytes();
    }

    public Bytes messageBytes() {
        final var detail = getMessage();
        return Bytes.of(detail.getBytes());
    }
}
