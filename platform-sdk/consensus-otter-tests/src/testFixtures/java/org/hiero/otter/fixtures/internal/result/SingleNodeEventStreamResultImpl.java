// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.SingleNodeEventStreamResult;

/**
 * Default implementation of {@link SingleNodeEventStreamResult}
 */
public class SingleNodeEventStreamResultImpl implements SingleNodeEventStreamResult {

    private static final BiPredicate<Path, BasicFileAttributes> EVENT_STREAM_FILE_FILTER =
            (path, attrs) -> attrs.isRegularFile() && path.toString().endsWith(".evts");

    private static final BiPredicate<Path, BasicFileAttributes> SIGNATURE_FILE_FILTER =
            (path, attrs) -> attrs.isRegularFile() && path.toString().endsWith(".evts_sig");

    private final NodeId nodeId;
    private final Path eventStreamDir;

    /**
     * Creates a new instance of {@link SingleNodeEventStreamResultImpl}.
     *
     * @param nodeId the {@link NodeId} of the node
     * @param baseDir the base directory where event stream files are stored
     * @param configuration the configuration of the node
     */
    public SingleNodeEventStreamResultImpl(
            @NonNull final NodeId nodeId, @NonNull final Path baseDir, @NonNull final Configuration configuration) {
        this.nodeId = requireNonNull(nodeId);
        this.eventStreamDir = baseDir.resolve("events_" + nodeId.id());

        final EventConfig eventConfig = configuration.getConfigData(EventConfig.class);
        if (!eventConfig.enableEventStreaming()) {
            throw new IllegalStateException("Event streaming is not enabled");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeId nodeId() {
        return nodeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Path> eventStreamFiles() {
        try (final Stream<Path> stream = Files.find(eventStreamDir, 1, EVENT_STREAM_FILE_FILTER)) {
            return stream.sorted().toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Exception while traversing event stream files", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Path> signatureFiles() {
        try (final Stream<Path> stream = Files.find(eventStreamDir, 1, SIGNATURE_FILE_FILTER)) {
            return stream.sorted().toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Exception while traversing signature files", e);
        }
    }
}
