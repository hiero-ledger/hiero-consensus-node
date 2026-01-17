// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication.handshake;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import java.io.IOException;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Exchanges software versions with the peer, either throws a {@link HandshakeException} or logs an error if the versions
 * do not match
 */
public class VersionCompareHandshake implements ProtocolRunnable {
    private static final Logger logger = LogManager.getLogger(VersionCompareHandshake.class);
    private final SemanticVersion version;
    private final boolean throwOnMismatch;

    /**
     * Calls {@link #VersionCompareHandshake(SemanticVersion, boolean)} with throwOnMismatch set to true
     */
    public VersionCompareHandshake(final SemanticVersion version) {
        this(version, true);
    }

    /**
     * @param version
     * 		the version of software this node is running
     * @param throwOnMismatch
     * 		if set to true, the protocol will throw an exception on a version mismatch. if set to false, it will log an
     * 		error and continue
     * @throws NullPointerException in case {@code version} parameter is {@code null}
     */
    public VersionCompareHandshake(final SemanticVersion version, final boolean throwOnMismatch) {
        Objects.requireNonNull(version, "version must not be null");
        this.version = version;
        this.throwOnMismatch = throwOnMismatch;
    }

    @Override
    public void runProtocol(final Connection connection)
            throws NetworkProtocolException, IOException, InterruptedException {
        connection.getDos().writePbjRecord(version, SemanticVersion.PROTOBUF);
        connection.getDos().flush();
        final SemanticVersion peerVersion = connection.getDis().readPbjRecord(SemanticVersion.PROTOBUF);
        if (SEMANTIC_VERSION_COMPARATOR.compare(version, peerVersion) != 0) {
            final String message = String.format(
                    "Incompatible versions. Self version is '%s', peer version is '%s'", version, peerVersion);
            if (throwOnMismatch) {
                throw new HandshakeException(message);
            } else {
                logger.error(EXCEPTION.getMarker(), message);
            }
        }
    }
}
