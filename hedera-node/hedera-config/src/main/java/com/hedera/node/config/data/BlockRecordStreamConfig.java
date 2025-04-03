// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;

/**
 * Configuration for record streams.
 *
 * @param logDir directory for writing record files
 * @param sidecarDir directory for writing sidecar files, it is specified relative to logDir; blank==same dir
 * @param logPeriod the number of seconds in consensus time between writing record files
 * @param queueCapacity ?? the number of files to queue for writing before blocking
 * @param sidecarMaxSizeMb the maximum size of a sidecar file in MB before rolling over to a new file
 * @param recordFileVersion the format version number for record files
 * @param signatureFileVersion the format version number for signature files
 * @param numOfBlockHashesInState the number of block hashes to keep in state for block history
 * @param streamFileProducer the type of stream file producer to use. Currently only "concurrent" is supported
 */
@ConfigData("hedera.recordStream")
public record BlockRecordStreamConfig(
        @ConfigProperty(defaultValue = "/opt/hgcapp/recordStreams") @NodeProperty String logDir,
        @ConfigProperty(defaultValue = "sidecar") @NodeProperty String sidecarDir,
        @ConfigProperty(defaultValue = "2") @Min(1) @NodeProperty int logPeriod,
        @ConfigProperty(defaultValue = "5000") @Min(1) @NodeProperty int queueCapacity,
        @ConfigProperty(defaultValue = "256") @Min(1) @Max(1024) @NetworkProperty int sidecarMaxSizeMb,
        @ConfigProperty(defaultValue = "6") @Min(1) @NetworkProperty int recordFileVersion,
        @ConfigProperty(defaultValue = "6") @Min(1) @NetworkProperty int signatureFileVersion,
        @ConfigProperty(defaultValue = "256") @Min(1) @Max(4096) @NetworkProperty int numOfBlockHashesInState,
        @ConfigProperty(defaultValue = "concurrent") @NetworkProperty String streamFileProducer) {}
