// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.hiero.consensus.model.node.NodeId;

/**
 * Configuration for the log error validator that checks for error messages in the logs.
 *
 * <p>This configuration can for example be used to specify errors that are expected and can be ignored.
 */
public class LogErrorConfig {

    private static final Logger log = LogManager.getLogger(LogErrorConfig.class);
    private final Type type;
    private final List<Marker> ignoredMarkers = new ArrayList<>();
    private final List<Long> ignoreNodes = new ArrayList<>();
    private final Level level;

    public enum Type {
        IGNORE_MARKER,
        IGNORE_NODE,
        MAX_LEVEL
    }

    private LogErrorConfig(
            final Type type,
            @NonNull final Level level,
            @NonNull List<LogMarker> markers,
            @NonNull final List<Node> nodes) {
        this.type = type;
        this.level = level;
        ignoredMarkers.addAll(markers.stream().map(LogMarker::getMarker).collect(Collectors.toSet()));
        ignoreNodes.addAll(nodes.stream().map(Node::getSelfId).map(NodeId::id).collect(Collectors.toSet()));
    }

    /**
     * Creates a configuration to ignore specific log markers.
     *
     * @param markers the log markers to ignore
     * @return a {@code LogErrorConfig} instance
     */
    @NonNull
    public static LogErrorConfig ignoreMarkers(@NonNull final LogMarker... markers) {
        Objects.requireNonNull(markers, "markers cannot be null");
        return new LogErrorConfig(Type.IGNORE_MARKER, Level.ERROR, List.of(markers), Collections.emptyList());
    }

    /**
     * Creates a configuration to ignore specific nodes
     *
     * @param nodes nodes to ignore
     * @return a {@code LogErrorConfig} instance
     */
    @NonNull
    public static LogErrorConfig ignoreNodes(@NonNull final Node... nodes) {
        Objects.requireNonNull(nodes, "nodes cannot be null");
        return new LogErrorConfig(Type.IGNORE_NODE, Level.ERROR, Collections.emptyList(), List.of(nodes));
    }

    @NonNull
    public static LogErrorConfig maxLogLevel(@NonNull final Level level) {
        Objects.requireNonNull(level, "level cannot be null");
        return new LogErrorConfig(Type.MAX_LEVEL, level, Collections.emptyList(), Collections.emptyList());
    }

    public Type getType() {
        return type;
    }

    public List<Marker> getIgnoredMarkers() {
        return ignoredMarkers;
    }

    public List<Long> getIgnoreNodes() {
        return ignoreNodes;
    }

    public Level getLevel() {
        return level;
    }
}
