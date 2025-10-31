// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;

/**
 * Describes an error which will be logged at a predetermined consensus time after genesis
 */
public class PlannedLogError implements PlannedIncident {

    /**
     * The amount of time after genesis that the error will be written to the log at
     */
    private Duration timeAfterGenesis;

    /**
     * The node IDs of the nodes that the error should be logged on
     */
    private List<NodeId> nodeIds;

    /**
     * Zero arg constructor for the constructable registry.
     */
    public PlannedLogError() {}

    /**
     * Constructor
     *
     * @param timeAfterGenesis the time after genesis that the error should be written to the log at
     */
    public PlannedLogError(@NonNull final Duration timeAfterGenesis, @NonNull final List<NodeId> nodeIds) {
        this.timeAfterGenesis = Objects.requireNonNull(timeAfterGenesis);
        this.nodeIds = Objects.requireNonNull(nodeIds);
    }

    public PlannedLogError(@NonNull final ReadableSequentialData in) {
        this.timeAfterGenesis = Duration.ofNanos(in.readLong());

        final int nodeCount = in.readInt();
        this.nodeIds = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            this.nodeIds.add(NodeId.of(in.readLong()));
        }
    }

    public void writeTo(@NonNull final WritableSequentialData output) {
        output.writeLong(timeAfterGenesis.toNanos());
        output.writeInt(nodeIds.size());
        for (NodeId nodeId : nodeIds) {
            output.writeLong(nodeId.id());
        }
    }

    public int getSizeInBytes() {
        int sizeInBytes = 0;
        sizeInBytes += Long.BYTES; // timeAfterGenesis
        sizeInBytes += Integer.BYTES; // nodeIds.size()
        for (NodeId ignored : nodeIds) {
            sizeInBytes += Long.BYTES;
        }

        return sizeInBytes;
    }

    /**
     * Create a new PlannedLogError from a string
     * <p>
     * String example:
     * <p>
     * "100:1-2-3"
     * <p>
     * Where the first number is the number of seconds after genesis that the error should be logged at, and the list of
     * numbers following the ":" are the node IDs of the nodes that the error should be logged on
     *
     * @param plannedLogErrorString the string to parse
     * @return the parsed PlannedLogError
     */
    @NonNull
    public static PlannedLogError fromString(@NonNull final String plannedLogErrorString) {
        final String[] timestampAndNodesStrings = plannedLogErrorString.strip().split(":");

        final int elapsedSeconds = Integer.parseInt(timestampAndNodesStrings[0]);
        final String[] nodeIdsStrings = timestampAndNodesStrings[1].split("-");

        final List<NodeId> nodeIds = new ArrayList<>();
        for (final String nodeIdString : nodeIdsStrings) {
            nodeIds.add(NodeId.of(Integer.parseInt(nodeIdString)));
        }

        return new PlannedLogError(Duration.ofSeconds(elapsedSeconds), nodeIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Duration getTimeAfterGenesis() {
        return timeAfterGenesis;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getDescriptor() {
        return "Log Error";
    }

    /**
     * Get the IDs of the nodes that the error should be logged on
     *
     * @return the IDs of the nodes that the error should be logged on
     */
    @NonNull
    public List<NodeId> getNodeIds() {
        return Collections.unmodifiableList(nodeIds);
    }
}
