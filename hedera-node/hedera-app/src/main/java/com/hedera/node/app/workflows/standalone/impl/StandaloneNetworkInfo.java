// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone.impl;

import static com.hedera.node.app.util.FileUtilities.createFileID;
import static com.hedera.node.app.util.FileUtilities.getFileContent;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NodeAddressBook;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link NetworkInfo} implementation that gets network information from the {@link FilesConfig#nodeDetails()}
 * file in a {@link State}. This lets a standalone executor handle transactions that make staking elections.
 * <p>
 * If the executor is to handle such transactions as if on a live network with this state, the node details file
 * must be present in the state and reflect the network's active address book.
 * <p>
 * The {@link NetworkInfo#selfNodeInfo()} implementation, however, returns a {@link NodeInfo} that is a complete
 * mock other than the software version, since the self-identity of the node executing a transaction clearly cannot
 * change <i>how</i> the transaction is executed.
 */
@Singleton
public class StandaloneNetworkInfo implements NetworkInfo {
    private static final Logger log = LogManager.getLogger(StandaloneNetworkInfo.class);

    private final Bytes ledgerId;
    private final ConfigProvider configProvider;
    private final NodeInfo selfNodeInfo;

    @Nullable
    private List<NodeInfo> nodeInfos;

    @Inject
    public StandaloneNetworkInfo(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider);
        final var config = configProvider.getConfiguration();
        this.ledgerId = config.getConfigData(LedgerConfig.class).id();
        this.selfNodeInfo = new NodeInfoImpl(0, AccountID.DEFAULT, 0, List.of(), Bytes.EMPTY, List.of(), false, null);
    }

    /**
     * Initializes this instance from {@link FilesConfig#nodeDetails()} file in the given state.
     * <p>
     * If the file is missing or cannot be parsed, no staking election will be treated as valid,
     * and staking transactions will not be executed as on any live network (which necessarily
     * has at least some nodes).
     * @param state the state to use
     */
    public void initFrom(@NonNull final State state) {
        requireNonNull(state);
        final var config = configProvider.getConfiguration();
        final var filesConfig = config.getConfigData(FilesConfig.class);
        final var nodeDetails = getFileContent(state, createFileID(filesConfig.nodeDetails(), config));
        try {
            final var nodeAddressBook = NodeAddressBook.PROTOBUF.parse(nodeDetails);
            nodeInfos = nodeAddressBook.nodeAddress().stream()
                    .<NodeInfo>map(address -> new NodeInfoImpl(
                            address.nodeId(),
                            address.nodeAccountIdOrThrow(),
                            address.stake(),
                            List.of(),
                            Bytes.EMPTY,
                            List.of(),
                            false,
                            null))
                    .toList();
        } catch (ParseException e) {
            log.warn("Failed to parse node details", e);
            nodeInfos = emptyList();
        }
    }

    @NonNull
    @Override
    public Bytes ledgerId() {
        return ledgerId;
    }

    @NonNull
    @Override
    public NodeInfo selfNodeInfo() {
        return selfNodeInfo;
    }

    @NonNull
    @Override
    public List<NodeInfo> addressBook() {
        return nodeInfosOrThrow();
    }

    @Nullable
    @Override
    public NodeInfo nodeInfo(final long nodeId) {
        return nodeInfosOrThrow().stream()
                .filter(node -> node.nodeId() == nodeId)
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean containsNode(final long nodeId) {
        return nodeInfo(nodeId) != null;
    }

    @Override
    public void updateFrom(final State state) {
        throw new UnsupportedOperationException("Not implemented");
    }

    private @NonNull List<NodeInfo> nodeInfosOrThrow() {
        return requireNonNull(nodeInfos, "Not initialized");
    }
}
