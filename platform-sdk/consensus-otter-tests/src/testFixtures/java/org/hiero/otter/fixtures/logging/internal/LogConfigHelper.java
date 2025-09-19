// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.logging.internal;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.FREEZE;
import static com.swirlds.logging.legacy.LogMarker.INVALID_EVENT_ERROR;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.logging.legacy.LogMarker.PLATFORM_STATUS;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.STATE_HASH;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static com.swirlds.logging.legacy.LogMarker.TESTING_EXCEPTIONS;
import static com.swirlds.logging.legacy.LogMarker.TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.THREADS;
import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Filter.Result;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.FilterComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.KeyValuePairComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.LayoutComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.hiero.consensus.model.node.NodeId;

/**
 * Utility class that provides commonly used filters, patterns, and other components for configuring the logging system
 * in a consistent way.
 */
public final class LogConfigHelper {

    /** Markers that are allowed in swirlds.log & console. */
    private static final Set<LogMarker> ALLOWED_MARKERS = Set.of(
            EXCEPTION,
            TESTING_EXCEPTIONS,
            SOCKET_EXCEPTIONS,
            INVALID_EVENT_ERROR,
            THREADS,
            STARTUP,
            PLATFORM_STATUS,
            RECONNECT,
            FREEZE,
            STATE_TO_DISK,
            DEMO_INFO,
            TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT,
            MERKLE_DB,
            STATE_HASH);

    private static final Set<LogMarker> IGNORED_CONSOLE_MARKERS = Set.of(STARTUP, MERKLE_DB, VIRTUAL_MERKLE_STATS);

    /** Default pattern for text-based appenders. */
    public static final String DEFAULT_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] }%notEmpty{[%marker] }%-5level %logger{36} - %msg %n";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private LogConfigHelper() {
        // utility
    }

    /**
     * Creates a composite filter that only ACCEPTs log events tagged with {@link LogMarker#STATE_HASH} and DENYs
     * everything else.
     *
     * @param builder the Log4j2 {@link ConfigurationBuilder} used to assemble the configuration
     * @return the fully configured {@link ComponentBuilder} holding the filter chain
     */
    @NonNull
    public static ComponentBuilder<?> configureHashStreamFilter(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder) {
        return builder.newComponent("Filters")
                .addComponent(builder.newFilter("MarkerFilter", Result.ACCEPT, Result.DENY)
                        .addAttribute("marker", STATE_HASH));
    }

    /**
     * Creates a filter that only allows log events originating from the given {@code nodeId} as indicated by the
     * {@code nodeId} entry in the {@link ThreadContext} map.
     *
     * @param builder the configuration builder
     * @param nodeId the node that should be allowed
     * @return a filter that ACCEPTs events for the specified node and DENYs all others
     */
    @NonNull
    public static FilterComponentBuilder createNodeOnlyFilter(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder, @NonNull final NodeId nodeId) {
        final String contextValue = Long.toString(nodeId.id());
        final KeyValuePairComponentBuilder keyValuePair = builder.newKeyValuePair("nodeId", contextValue);

        return builder.newFilter("ThreadContextMapFilter", Result.NEUTRAL, Result.DENY)
                .addComponent(keyValuePair);
    }

    @NonNull
    private static String getJsonNodeId(@NonNull final NodeId nodeId) {
        try {
            return objectMapper.writeValueAsString(nodeId);
        } catch (final JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a filter that DENYs all log events coming from the specified {@code nodeId}. This is the counterpart to
     * {@link #createNodeOnlyFilter(ConfigurationBuilder, NodeId)} and is mainly used for console output where we want
     * to exclude per-node log lines when routing is enabled.
     *
     * @param builder the configuration builder
     * @param nodeId the node that should be excluded
     * @return a filter that DENYs the specified node and NEUTRAL for all others
     */
    @NonNull
    public static FilterComponentBuilder createExcludeNodeFilter(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder, @NonNull final NodeId nodeId) {
        final String contextValue = Long.toString(nodeId.id());
        final KeyValuePairComponentBuilder keyValuePair = builder.newKeyValuePair("nodeId", contextValue);

        return builder.newFilter("ThreadContextMapFilter", Result.DENY, Result.NEUTRAL)
                .addComponent(keyValuePair);
    }

    /**
     * Creates the set of filters that WHITE-LISTs all {@link #ALLOWED_MARKERS}. Any log event not carrying one of those
     * markers will be DENYed.
     *
     * @param builder the configuration builder
     * @return a composite {@link ComponentBuilder} holding all marker filters
     */
    @NonNull
    public static ComponentBuilder<?> createAllowedMarkerFilters(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder) {
        final ComponentBuilder<?> allowedMarkerFilters = builder.newComponent("Filters");
        for (final LogMarker marker : ALLOWED_MARKERS) {
            allowedMarkerFilters.addComponent(builder.newFilter("MarkerFilter", Result.ACCEPT, Result.NEUTRAL)
                    .addAttribute("marker", marker));
        }
        // deny everything else
        allowedMarkerFilters.addComponent(builder.newFilter("DenyAllFilter", Result.DENY, Result.DENY));
        return allowedMarkerFilters;
    }

    /**
     * Creates a filter component that DENYs all events containing any marker listed in
     * {@link #IGNORED_CONSOLE_MARKERS}. This is useful for suppressing log output to the console
     *
     * @param builder the configuration builder
     * @return a composite {@link ComponentBuilder} that suppresses unwanted markers
     */
    @NonNull
    public static ComponentBuilder<?> creatIgnoreMarkerFilters(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder) {
        final ComponentBuilder<?> ignoredMarkerFilters = builder.newComponent("Filters");
        for (final LogMarker marker : IGNORED_CONSOLE_MARKERS) {
            ignoredMarkerFilters.addComponent(builder.newFilter("MarkerFilter", Result.DENY, Result.NEUTRAL)
                    .addAttribute("marker", marker));
        }

        return ignoredMarkerFilters;
    }

    /**
     * Builds a simple {@code ThresholdFilter} that only allows {@code INFO} and higher level log events to pass
     * through.
     *
     * @param builder the configuration builder
     * @return the created filter component
     */
    @NonNull
    public static FilterComponentBuilder createThresholdFilter(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder) {
        return builder.newFilter("ThresholdFilter", Result.NEUTRAL, Result.DENY).addAttribute("level", Level.INFO);
    }

    /**
     * Helper method that constructs a {@code File} appender with sane defaults and attaches the supplied filters.
     *
     * @param builder the configuration builder
     * @param name appender name
     * @param layout the layout to use when writing log lines
     * @param fileName fully-qualified path of the log file
     * @param filters optional filters; may be {@code null} or empty
     * @return the created file appender component
     */
    @NonNull
    public static AppenderComponentBuilder createFileAppender(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder,
            @NonNull final String name,
            @NonNull final LayoutComponentBuilder layout,
            @NonNull final String fileName,
            @Nullable final ComponentBuilder<?>... filters) {

        return builder.newAppender(name, "File")
                .addAttribute("fileName", fileName)
                .addAttribute("append", true)
                .addComponent(combineFilters(builder, filters))
                .add(layout);
    }

    /**
     * Aggregates all supplied {@code filters} under a single {@code <Filters>} element, as required by Log4j2 XML
     * schema.
     *
     * @param builder the configuration builder
     * @param filters the filters to combine; may be {@code null}
     * @return a composite filter component
     */
    @NonNull
    public static ComponentBuilder<?> combineFilters(
            @NonNull final ConfigurationBuilder<BuiltConfiguration> builder,
            @Nullable final ComponentBuilder<?>... filters) {
        final ComponentBuilder<?> compositeFilters = builder.newComponent("Filters");
        if (filters != null) {
            for (final ComponentBuilder<?> filter : filters) {
                compositeFilters.addComponent(filter);
            }
        }
        return compositeFilters;
    }
}
