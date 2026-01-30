// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NodeAddressBook;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.app.util.FileUtilities;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages retrieval of the self node's account ID from state (File 0.0.102, node details).
 */
@Singleton
public class SelfNodeAccountIdManagerImpl implements SelfNodeAccountIdManager {
    private static final Logger logger = LogManager.getLogger(SelfNodeAccountIdManagerImpl.class);
    private final ConfigProvider configProvider;
    private final NodeInfo nodeInfo;
    private final State state;

    private final AtomicReference<AccountID> accountId = new AtomicReference<>();

    /**
     * Primary constructor used in production: reads self node account ID from state node details file.
     */
    public SelfNodeAccountIdManagerImpl(
            @NonNull final ConfigProvider configProvider,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final State state) {
        this.configProvider = requireNonNull(configProvider);
        this.nodeInfo = requireNonNull(networkInfo).selfNodeInfo();
        this.state = requireNonNull(state);
    }

    /**
     * Retrieves the self node's account ID.
     * @return the self node's account ID
     */
    public AccountID getSelfNodeAccountId() {
        if (accountId.get() == null) {
            initSelfNodeAccountId();
        }

        return accountId.get();
    }

    /**
     * Sets the self node's account ID in-memory.
     * @param accountId the new account ID
     */
    public void setSelfNodeAccountId(@NonNull final AccountID accountId) {
        this.accountId.set(requireNonNull(accountId));
    }

    private void initSelfNodeAccountId() {
        if (state == null) {
            accountId.set(nodeInfo.accountId());
            return;
        }
        final var config = configProvider.getConfiguration();
        final var filesConfig = config.getConfigData(FilesConfig.class);
        try {
            final var nodeDetailsId = FileUtilities.createFileID(filesConfig.nodeDetails(), config);
            final var fileStore = new ReadableStoreFactoryImpl(state).readableStore(ReadableFileStore.class);
            final var nodeDetailsFile = fileStore.getFileLeaf(nodeDetailsId);
            final Bytes bytes = (nodeDetailsFile == null) ? Bytes.EMPTY : nodeDetailsFile.contents();
            if (bytes.length() == 0) {
                logger.info(
                        "Node details file ({}) missing or empty; falling back to self NodeInfo",
                        filesConfig.nodeDetails());
                accountId.set(nodeInfo.accountId());
                return;
            }
            final var book = NodeAddressBook.PROTOBUF.parse(bytes);
            final var selfNodeId = nodeInfo.nodeId();
            final var maybeEntry = book.nodeAddress().stream()
                    .filter(addr -> addr.nodeId() == selfNodeId)
                    .findFirst();
            if (maybeEntry.isPresent() && maybeEntry.get().hasNodeAccountId()) {
                accountId.set(maybeEntry.get().nodeAccountIdOrThrow());
            } else {
                logger.warn("Self node id {} not found in node details; using NodeInfo account id", selfNodeId);
                accountId.set(nodeInfo.accountId());
            }
        } catch (ParseException e) {
            logger.warn(
                    "Failed to parse node details (file {}); using NodeInfo account id", filesConfig.nodeDetails(), e);
            accountId.set(nodeInfo.accountId());
        }
    }
}
