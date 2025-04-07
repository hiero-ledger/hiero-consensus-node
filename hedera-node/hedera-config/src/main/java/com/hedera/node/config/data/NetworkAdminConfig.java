// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.hedera.node.config.types.DiskNetworkExport;
import com.hedera.node.config.types.HederaFunctionalitySet;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration for the NetworkAdmin service. Contains only the path to the upgrade artifacts directory.
 *
 * @param upgradeArtifactsPath path to the location where upgrade files are stored once uncompressed, and upgrade
 *                             marker files are written
 * @param keysPath path to the generated public key *.pem files during freeze prepare upgrade
 * @param upgradeSysFilesLoc path to the location where post-upgrade system files are located
 * @param upgradeFeeSchedulesFile name of the file containing the post-upgrade fee schedules
 * @param upgradeThrottlesFile name of the file containing the post-upgrade throttles
 * @param upgradePropertyOverridesFile name of the file containing the post-upgrade override properties
 * @param upgradePermissionOverridesFile name of the file containing the post-upgrade override permissions
 */
@ConfigData("networkAdmin")
public record NetworkAdminConfig(
        @ConfigProperty(defaultValue = "data/upgrade/current") @NodeProperty String upgradeArtifactsPath,
        @ConfigProperty(defaultValue = "data/upgrade/current/data/keys") @NodeProperty String keysPath,
        @ConfigProperty(defaultValue = "data/config") String upgradeSysFilesLoc,
        @ConfigProperty(defaultValue = "feeSchedules.json") String upgradeFeeSchedulesFile,
        @ConfigProperty(defaultValue = "throttles.json") String upgradeThrottlesFile,
        @ConfigProperty(defaultValue = "application-override.properties") String upgradePropertyOverridesFile,
        @ConfigProperty(defaultValue = "api-permission-override.properties") String upgradePermissionOverridesFile,
        @ConfigProperty(defaultValue = "node-admin-keys.json") String upgradeNodeAdminKeysFile,
        @ConfigProperty(
                        defaultValue =
                                "HintsKeyPublication,HintsPreprocessingVote,HintsPartialSignature,HistoryAssemblySignature,HistoryProofKeyPublication,HistoryProofVote,CrsPublication")
                @NetworkProperty
                HederaFunctionalitySet nodeTransactionsAllowList,
        @ConfigProperty(defaultValue = "network.json") @NodeProperty String diskNetworkExportFile,
        @ConfigProperty(defaultValue = "NEVER") DiskNetworkExport diskNetworkExport,
        @ConfigProperty(defaultValue = "true") @NodeProperty boolean exportCandidateRoster,
        @ConfigProperty(defaultValue = "candidate-roster.json") @NodeProperty String candidateRosterExportFile,
        @ConfigProperty(defaultValue = "50") @NetworkProperty int timesToTrySubmission,
        @ConfigProperty(defaultValue = "5s") @NetworkProperty Duration retryDelay,
        @ConfigProperty(defaultValue = "10") @NetworkProperty int distinctTxnIdsToTry,
        @ConfigProperty(defaultValue = "true") @NodeProperty boolean preserveStateWeightsDuringOverride,
        // Default value is the working directory
        @ConfigProperty(defaultValue = "") @NodeProperty String configTxtPath) {}
