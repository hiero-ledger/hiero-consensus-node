// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class SelfNodeAccountIdManagerImpl implements SelfNodeAccountIdManager {
    private static final Logger logger = LogManager.getLogger(SelfNodeAccountIdManagerImpl.class);

    private final ConfigProvider configProvider;
    private final NodeInfo nodeInfo;

    private static final String NODE_ACCOUNT_ID_FILE = "node_account_id.txt";

    @Inject
    public SelfNodeAccountIdManagerImpl(@NonNull ConfigProvider configProvider, @NonNull NodeInfo selfNodeInfo) {

        this.configProvider = configProvider;
        this.nodeInfo = selfNodeInfo;
    }

    public String getSelfNodeAccountId() {
        // If NODE_ACCOUNT_ID_FILE is missing, create new one
        final BlockRecordStreamConfig recordStreamConfig =
                configProvider.getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        final Path recordsStreamPath = getAbsolutePath(recordStreamConfig.logDir());
        final var filePath = recordsStreamPath.resolve(NODE_ACCOUNT_ID_FILE);
        try {
            // if the file don't exist we should create one
            if (!filePath.toFile().exists()) {
                String content = nodeInfo.accountId().shardNum() + "."
                        + nodeInfo.accountId().realmNum()
                        + "."
                        + nodeInfo.accountId().accountNum();
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);
                logger.info("Wrote node account id file at {}", filePath);
            }

            return Files.readString(filePath);

        } catch (IOException e) {
            logger.info("Failed to read node account id from {} file", NODE_ACCOUNT_ID_FILE, e);
            return HapiUtils.asAccountString(nodeInfo.accountId());
        }
    }

    public void setSelfNodeAccountId(final AccountID accountId) {
        // If NODE_ACCOUNT_ID_FILE is missing create new one
        final BlockRecordStreamConfig recordStreamConfig =
                configProvider.getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        final Path recordsStreamPath = getAbsolutePath(recordStreamConfig.logDir());
        final var filePath = recordsStreamPath.resolve(NODE_ACCOUNT_ID_FILE);
        try {
            // if the file don't exist we should create one
            if (!filePath.toFile().exists()) {
                String content = accountId.shardNum() + "." + accountId.realmNum() + "." + accountId.accountNum();
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);
                logger.info("Wrote node account id file at {}", filePath);
            }
        } catch (IOException e) {
            logger.info("Failed to read node account id from {} file", NODE_ACCOUNT_ID_FILE, e);
        }
    }
}
