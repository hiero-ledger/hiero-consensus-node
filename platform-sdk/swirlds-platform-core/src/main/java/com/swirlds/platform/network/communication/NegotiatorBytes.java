// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication;

import java.io.EOFException;

/**
 * Constants use by the {@link Negotiator}
 */
public final class NegotiatorBytes {
    /** sent to keep the connection alive */
    public static final int KEEPALIVE = 255;
    /** accept an initiated protocol */
    public static final int ACCEPT = 254;
    /** reject an initiated protocol */
    public static final int REJECT = 253;
    /** maximum number of protocols supported */
    public static final int MAX_NUMBER_OF_PROTOCOLS = 252;
    /** value used when an int has no initialized value */
    public static final int UNINITIALIZED = Integer.MIN_VALUE;
    /** max value for a byte when converted to an int by an input or output stream */
    public static final int MAX_BYTE = 255;
    /** min value for a byte when converted to an int by an input or output stream */
    public static final int MIN_BYTE = 0;
    /** the byte returned when the end of stream has been reached */
    public static final int END_OF_STREAM_BYTE = -1;

    private NegotiatorBytes() {}

    /**
     * Checks if the supplied byte is a valid value in the protocol negotiation
     *
     * @param b
     * 		the byte to check
     * @throws NegotiationException
     * 		if the byte is not valid
     * @throws EOFException
     * 		if the byte represents the end of the stream
     */
    public static void checkByte(final int b) throws NegotiationException, EOFException {
        if (b == END_OF_STREAM_BYTE) {
            throw new EOFException("the end of the stream has been reached");
        }
        if (b < MIN_BYTE || b > MAX_BYTE) {
            throw new NegotiationException("not a valid byte: " + b);
        }
    }
}
