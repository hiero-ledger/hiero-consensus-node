// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.NodesConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages the persistent storage and retrieval of the self node's account ID.
 * <p>
 * The account ID is stored in a file named {@code node_account_id.txt} on disk to ensure it is preserved
 * across restarts and upgrades. On first access, if the file does not exist (e.g., for new nodes),
 * it is created using the current node's account ID from the "NodeInfo selfInfo". Subsequent accesses
 * will return the value from the file.
 */
@Singleton
public class SelfNodeAccountIdManagerImpl implements SelfNodeAccountIdManager {
    private static final Logger logger = LogManager.getLogger(SelfNodeAccountIdManagerImpl.class);
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final NodeInfo nodeInfo;
    private final Path filePath;

    private AccountID accountId;

    @Inject
    public SelfNodeAccountIdManagerImpl(@NonNull ConfigProvider configProvider, @NonNull NetworkInfo networkInfo) {
        logger.error("### SelfNodeAccountIdManagerImpl INSTANCE CREATED: {}",
                System.identityHashCode(this));
        this.nodeInfo = networkInfo.selfNodeInfo();
        final NodesConfig nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);
        this.filePath = getAbsolutePath(nodesConfig.nodeGeneratedDir()).resolve(nodesConfig.nodeAccountIdFile());
    }

    /**
     * Retrieves the self node's account ID.
     * @return the self node's account ID
     */
    public AccountID getSelfNodeAccountId() {
        // First, optimistic read
        lock.readLock().lock();
        try {
            if (accountId != null) {
                logger.info(" $$$$$ {} - Account ID {}", Thread.currentThread(), accountId);
                return accountId;
            }
        } finally {
            lock.readLock().unlock();
        }

        // If null, initialize under WRITE lock
        lock.writeLock().lock();
        try {
            if (accountId == null) { // double-check
                initSelfNodeAccountId();
            }
            logger.info(" $$$$$ {} - Account ID {}", Thread.currentThread(), accountId);
            return accountId;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Creates or updates the file {@code node_account_id.txt} containing the self node's account ID.
     * <p>
     * This method writes the provided {@link AccountID} to the persistent file,
     * overwriting any existing value.
     *
     * @param accountId the new account ID to persist
     */
    public void setSelfNodeAccountId(@NonNull final AccountID accountId) {
        lock.writeLock().lock();
        try {
            logger.info("Setting up new self node account ID to {}", accountId);
            this.accountId = requireNonNull(accountId);
            writeAccountIdFile(accountId);
        } catch (IOException e) {
            logger.error("Failed to write node account id to {}", filePath, e);
        } finally {
            lock.writeLock().unlock();
        }

    }

    private void initSelfNodeAccountId() {
        try {
            logger.info("Initializing self node account ID");
            logger.info("Self nodeInfo.accountId: {}", nodeInfo.accountId().accountNum());

            // if the file don't exist, create one
            if (!filePath.toFile().exists()) {
                logger.info("Creating new file from nodeInfo.accountId");
                writeAccountIdFile(nodeInfo.accountId());
            }

            final var accountIdString = Files.readString(filePath);
            String[] parts = accountIdString.split("[.]");
            accountId = AccountID.newBuilder()
                    .shardNum(Long.parseLong(parts[0]))
                    .realmNum(Long.parseLong(parts[1]))
                    .accountNum(Long.parseLong(parts[2]))
                    .build();
            logger.info("Added in memory account id - {}", accountId.accountNum());

        } catch (IOException e) {
            logger.error("Failed to read node account id from {}", filePath, e);
            accountId = nodeInfo.accountId();
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            logger.error("Failed to parse account id from {}", filePath, e);
            accountId = nodeInfo.accountId();
        }
    }

    private void writeAccountIdFile(AccountID accountId) throws IOException {
        Files.createDirectories(filePath.getParent());
        String content = accountId.shardNum() + "." + accountId.realmNum() + "." + accountId.accountNum();
        Files.writeString(filePath, content);
        logger.info("Wrote node account id file at {}", filePath);
        logger.info("With node account id - {}", accountId);
    }
}
