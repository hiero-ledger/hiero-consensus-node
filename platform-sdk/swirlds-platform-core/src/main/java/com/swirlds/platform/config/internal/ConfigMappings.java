// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config.internal;

import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.config.extensions.sources.ConfigMapping;
import com.swirlds.config.extensions.sources.MappedConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Adds mappings for configuration parameters that have changed their names, so that the old names can still be used and
 * supported.
 *
 * @see ConfigMapping
 * @see MappedConfigSource
 */
public final class ConfigMappings {
    private ConfigMappings() {}

    static final List<ConfigMapping> MAPPINGS = List.of(
            new ConfigMapping("consensus.roundsNonAncient", "state.roundsNonAncient"),
            new ConfigMapping("consensus.roundsExpired", "state.roundsExpired"),
            new ConfigMapping("consensus.coinFreq", "coinFreq"),
            new ConfigMapping("state.signedStateFreq", "signedStateFreq"),
            new ConfigMapping("state.requireStateLoad", "requireStateLoad"),
            new ConfigMapping("state.emergencyStateFileName", "emergencyStateFileName"),
            new ConfigMapping("state.checkSignedStateFromDisk", "checkSignedStateFromDisk"),
            new ConfigMapping("event.maxEventQueueForCons", "maxEventQueueForCons"),
            new ConfigMapping("event.eventIntakeQueueThrottleSize", "eventIntakeQueueThrottleSize"),
            new ConfigMapping("event.eventIntakeQueueSize", "eventIntakeQueueSize"),
            new ConfigMapping("event.randomEventProbability", "randomEventProbability"),
            new ConfigMapping("event.staleEventPreventionThreshold", "staleEventPreventionThreshold"),
            new ConfigMapping("event.rescueChildlessInverseProbability", "rescueChildlessInverseProbability"),
            new ConfigMapping("event.eventStreamQueueCapacity", "eventStreamQueueCapacity"),
            new ConfigMapping("event.eventsLogPeriod", "eventsLogPeriod"),
            new ConfigMapping("event.eventsLogDir", "eventsLogDir"),
            new ConfigMapping("event.enableEventStreaming", "enableEventStreaming"),
            new ConfigMapping("metrics.halfLife", "halfLife"),
            new ConfigMapping("metrics.csvWriteFrequency", "csvWriteFrequency"),
            new ConfigMapping("metrics.csvOutputFolder", "csvOutputFolder"),
            new ConfigMapping("metrics.csvFileName", "csvFileName"),
            new ConfigMapping("metrics.csvAppend", "csvAppend"),
            new ConfigMapping("metrics.disableMetricsOutput", "disableMetricsOutput"),
            new ConfigMapping("prometheus.endpointEnabled", "prometheusEndpointEnabled"),
            new ConfigMapping("prometheus.endpointPortNumber", "prometheusEndpointPortNumber"),
            new ConfigMapping("prometheus.endpointMaxBacklogAllowed", "prometheusEndpointMaxBacklogAllowed"),
            new ConfigMapping("paths.settingsUsedDir", "settingsUsedDir"),
            new ConfigMapping("paths.keysDirPath", "keysDirPath"),
            new ConfigMapping("paths.appsDirPath", "appsDirPath"),
            new ConfigMapping("paths.logPath", "logPath"),
            new ConfigMapping("socket.ipTos", "socketIpTos"),
            new ConfigMapping("socket.bufferSize", "bufferSize"),
            new ConfigMapping("socket.timeoutSyncClientSocket", "timeoutSyncClientSocket"),
            new ConfigMapping("socket.timeoutSyncClientConnect", "timeoutSyncClientConnect"),
            new ConfigMapping("socket.timeoutServerAcceptConnect", "timeoutServerAcceptConnect"),
            new ConfigMapping("socket.useLoopbackIp", "useLoopbackIp"),
            new ConfigMapping("socket.tcpNoDelay", "tcpNoDelay"),
            new ConfigMapping("socket.deadlockCheckPeriod", "deadlockCheckPeriod"),
            new ConfigMapping("jvmPauseDetectorSleepMs", "jVMPauseDetectorSleepMs"),
            new ConfigMapping("jvmPauseReportMs", "jVMPauseReportMs"),
            new ConfigMapping("reconnect.asyncOutputStreamFlush", "reconnect.asyncOutputStreamFlushMilliseconds"),
            new ConfigMapping("reconnect.maxAckDelay", "reconnect.maxAckDelayMilliseconds"));

    /**
     * Add all known aliases to the provided config source
     *
     * @param configSource the source to add aliases to
     * @return the original source with added aliases
     */
    @NonNull
    public static ConfigSource addConfigMapping(@NonNull final ConfigSource configSource) {
        PlatformConfigUtils.logAppliedMappedProperties(configSource.getPropertyNames());
        final MappedConfigSource withAliases = new MappedConfigSource(configSource);
        MAPPINGS.forEach(withAliases::addMapping);

        return withAliases;
    }
}
