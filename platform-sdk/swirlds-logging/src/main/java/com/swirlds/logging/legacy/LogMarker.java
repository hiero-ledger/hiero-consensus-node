// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

/**
 * Definitions of all log markers.
 */
public enum LogMarker {

    /**
     * log all exceptions, and serious problems. These should never happen there is a bug in the code. In most cases,
     * this should include a full stack trace of the exception.
     */
    EXCEPTION(LogMarkerType.ERROR),

    /**
     * exceptions that shouldn't happen during testing, but can happen in production if there is a malicious
     * node. This should be turned off in production so that a malicious node cannot clutter the logs
     */
    TESTING_EXCEPTIONS(LogMarkerType.ERROR),

    /**
     * log the 4 sync exceptions (EOFException, SocketTimeoutException, SocketException, IOException)
     */
    SOCKET_EXCEPTIONS(LogMarkerType.ERROR),

    /**
     * log socket exceptions related to connecting to a node, this is a separate marker to avoid filling the log file
     * in case one node is down, or it has not started yet
     */
    TCP_CONNECT_EXCEPTIONS(LogMarkerType.ERROR),

    /**
     * exceptions that shouldn't happen during testing, except for all nodes in reconnect test
     */
    TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT(LogMarkerType.ERROR),

    /**
     * log errors.
     */
    ERROR(LogMarkerType.ERROR),

    /**
     * log any events received which were not valid
     */
    INVALID_EVENT_ERROR(LogMarkerType.ERROR),

    /**
     * logs events related to the startup of the application
     */
    STARTUP(LogMarkerType.INFO),

    /**
     * Relevant information about each step of the sync without too much logging
     */
    SYNC_INFO(LogMarkerType.INFO),

    /**
     * All network related
     */
    NETWORK(LogMarkerType.INFO),

    /**
     * log each event as it's added to the hashgraph
     */
    ADD_EVENT(LogMarkerType.INFO),

    /**
     * Marker for config
     */
    CONFIG(LogMarkerType.INFO),
    /**
     * Marker for CLI
     */
    CLI(LogMarkerType.INFO),

    /**
     * log each event as it's added to the intake queue
     */
    INTAKE_EVENT(LogMarkerType.INFO),

    /**
     * log when threads are being stopped and/or joined
     */
    THREADS(LogMarkerType.INFO),

    /**
     * logs the certificates either loaded of created
     */
    CERTIFICATES(LogMarkerType.INFO),

    /**
     * logs events related to event streaming
     */
    EVENT_STREAM(LogMarkerType.INFO),

    OBJECT_STREAM(LogMarkerType.INFO),

    OBJECT_STREAM_FILE(LogMarkerType.INFO),

    /**
     * logs events related platform freezing
     */
    FREEZE(LogMarkerType.INFO),

    /**
     * logs info related to signed states being saved to disk
     */
    STATE_TO_DISK(LogMarkerType.INFO),

    /**
     * logs info related to the signed state's hash
     */
    STATE_HASH(LogMarkerType.INFO),

    /**
     * log file signing events.
     */
    FILE_SIGN(LogMarkerType.INFO),

    /**
     * logs related to a signed state
     */
    SIGNED_STATE(LogMarkerType.INFO),

    /**
     * logs events related to reconnect
     */
    RECONNECT(LogMarkerType.INFO),

    /**
     * logs related to PTA runs. It is useful during debugging PTA with info from the platform
     */
    DEMO_INFO(LogMarkerType.INFO),

    /**
     * logs related to stale events
     */
    STALE_EVENTS(LogMarkerType.INFO),

    /**
     * log all platform status changes
     */
    PLATFORM_STATUS(LogMarkerType.INFO),

    /**
     * Detail information about MerkleDb.
     */
    MERKLE_DB(LogMarkerType.INFO),

    /**
     * Logs stats related to Virtual Merkle (nodes, map, etc).
     */
    VIRTUAL_MERKLE_STATS(LogMarkerType.INFO),

    /** logs related to network protocol negotiation */
    PROTOCOL_NEGOTIATION(LogMarkerType.INFO),

    /**
     * detailed logs related to consensus voting, very verbose, should be used only for debugging
     */
    CONSENSUS_VOTING(LogMarkerType.INFO),

    /**
     * logging related to metric system
     */
    METRICS(LogMarkerType.INFO);

    private final LogMarkerType type;
    private final Marker marker;

    LogMarker(final LogMarkerType type) {
        this.type = type;
        this.marker = MarkerManager.getMarker(name());
    }

    /**
     * @return the com.swirlds.logging.legacy.LogMarkerType type instance referenced by this instance
     */
    public LogMarkerType getType() {
        return type;
    }

    /**
     * @return the org.apache.logging.log4j.Marker type instance referenced by this instance
     */
    public Marker getMarker() {
        return marker;
    }
}
