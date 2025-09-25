// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.utils;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;

/**
 * Constants and utility methods used by the container setup code and the container code itself.
 */
public class ContainerConstants {

    /**
     * Working directory of the container
     */
    public static final String CONTAINER_APP_WORKING_DIR = "/opt/DockerApp";

    /**
     * The port to open to allow connections to the
     * {@link org.hiero.otter.fixtures.container.proto.ContainerControlServiceGrpc}
     */
    public static final int CONTAINER_CONTROL_PORT = 8080;

    /**
     * The port to open to allow connections to the
     * {@link org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc}
     */
    public static final int NODE_COMMUNICATION_PORT = 8081;

    /**
     * The base debug port used to debug the
     * {@link org.hiero.otter.fixtures.container.proto.ContainerControlServiceGrpc}. The specific debug port for each
     * node is this value plus the node id.
     */
    private static final int CONTAINER_CONTROL_BASE_DEBUG_PORT = 5005;

    /**
     * The base debug port used to debug the
     * {@link org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc}. The specific debug port for each
     * node is this value plus the node id.
     */
    private static final int NODE_COMMUNICATION_BASE_DEBUG_PORT = 6005;

    /**
     * Returns the debug port for the {@link org.hiero.otter.fixtures.container.proto.ContainerControlServiceGrpc} based
     * on the node id.
     *
     * @param nodeId the id of the node to get the debug port for
     * @return the debug port
     */
    public static int getContainerControlDebugPort(@NonNull final NodeId nodeId) {
        return CONTAINER_CONTROL_BASE_DEBUG_PORT + (int) nodeId.id();
    }

    /**
     * Returns the debug port for the {@link org.hiero.otter.fixtures.container.proto.NodeCommunicationServiceGrpc}
     * based on the node id.
     *
     * @param nodeId the id of the node to get the debug port for
     * @return the debug port
     */
    public static int getNodeCommunicationDebugPort(@NonNull final NodeId nodeId) {
        return NODE_COMMUNICATION_BASE_DEBUG_PORT + (int) nodeId.id();
    }

    /**
     * Returns the Java tool options to enable debugging and attaching to the container.
     *
     * @param debugPort the debug port to expose
     * @return the Java tool options string
     */
    @NonNull
    public static String getJavaToolOptions(final int debugPort) {
        return String.format(
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:%s -Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener",
                debugPort);
    }
}
