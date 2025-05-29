// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.BLOCK_STREAMS_DIR;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.RECORD_STREAMS_DIR;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.SAVED_STATES_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CONFIG_TXT;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CURRENT_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.DATA_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.GENESIS_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.NODE_ADMIN_KEYS_JSON;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.OUTPUT_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.UPGRADE_DIR;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.Hedera;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;

public abstract class AbstractNode implements HederaNode {
    private static final String HGCAA_LOG = "hgcaa.log";
    private static final String SWIRLDS_LOG = "swirlds.log";
    private static final String LOG4J2_XML = "log4j2.xml";

    protected NodeMetadata metadata;

    protected AbstractNode(@NonNull final NodeMetadata metadata) {
        this.metadata = metadata;
    }

    @Override
    public String getHost() {
        return metadata.host();
    }

    @Override
    public int getGrpcPort() {
        return metadata.grpcPort();
    }

    @Override
    public int getGrpcNodeOperatorPort() {
        return metadata.grpcNodeOperatorPort();
    }

    @Override
    public long getNodeId() {
        return metadata.nodeId();
    }

    @Override
    public String getName() {
        return metadata.name();
    }

    @Override
    public AccountID getAccountId() {
        return metadata.accountId();
    }

    @Override
    public Path getExternalPath(@NonNull final ExternalPath path) {
        requireNonNull(path);
        final var workingDir = requireNonNull(metadata.workingDir());
        return switch (path) {
            case WORKING_DIR -> workingDir;
            case APPLICATION_LOG -> workingDir.resolve(OUTPUT_DIR).resolve(HGCAA_LOG);
            case SWIRLDS_LOG -> workingDir.resolve(OUTPUT_DIR).resolve(SWIRLDS_LOG);
            case ADDRESS_BOOK -> workingDir.resolve(CONFIG_TXT);
            case GENESIS_PROPERTIES ->
                workingDir.resolve(DATA_DIR).resolve(CONFIG_DIR).resolve(GENESIS_PROPERTIES);
            case NODE_ADMIN_KEYS_JSON ->
                workingDir.resolve(DATA_DIR).resolve(CONFIG_DIR).resolve(NODE_ADMIN_KEYS_JSON);
            case APPLICATION_PROPERTIES ->
                workingDir.resolve(DATA_DIR).resolve(CONFIG_DIR).resolve(APPLICATION_PROPERTIES);
            case LOG4J2_XML -> workingDir.resolve(LOG4J2_XML);
            case DATA_CONFIG_DIR -> workingDir.resolve(DATA_DIR).resolve(CONFIG_DIR);
            case RECORD_STREAMS_DIR ->
                workingDir
                        .resolve(DATA_DIR)
                        .resolve(RECORD_STREAMS_DIR)
                        .resolve(String.format(
                                "record%s.%s.%s",
                                getAccountId().shardNum(),
                                getAccountId().realmNum(),
                                getAccountId().accountNumOrThrow()));
            case BLOCK_STREAMS_DIR ->
                workingDir
                        .resolve(DATA_DIR)
                        .resolve(BLOCK_STREAMS_DIR)
                        .resolve(String.format(
                                "block-%s.%s.%s",
                                getAccountId().shardNum(),
                                getAccountId().realmNum(),
                                getAccountId().accountNumOrThrow()));
            case UPGRADE_ARTIFACTS_DIR ->
                workingDir.resolve(DATA_DIR).resolve(UPGRADE_DIR).resolve(CURRENT_DIR);
            case SAVED_STATES_DIR ->
                workingDir
                        .resolve(DATA_DIR)
                        .resolve(SAVED_STATES_DIR)
                        .resolve(Hedera.APP_NAME)
                        .resolve("" + getNodeId())
                        .resolve(Hedera.SWIRLD_NAME);
        };
    }

    @Override
    public NodeMetadata metadata() {
        return metadata;
    }
}
