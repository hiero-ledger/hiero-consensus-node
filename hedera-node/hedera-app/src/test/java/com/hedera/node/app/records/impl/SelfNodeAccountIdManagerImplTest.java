// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_LABEL;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.NodeAddress;
import com.hedera.hapi.node.base.NodeAddressBook;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.records.impl.producers.formats.SelfNodeAccountIdManagerImpl;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SelfNodeAccountIdManagerImplTest {

    private SelfNodeAccountIdManagerImpl subject;

    @Mock
    private static ConfigProvider configProvider;

    @Mock
    private static VersionedConfiguration config;

    @Mock
    private static FilesConfig filesConfig;

    @Mock
    private static HederaConfig hederaConfig;

    @Mock
    private static NetworkInfo networkInfo;

    @Mock
    private State state;

    @Mock
    private ReadableStates readableStates;

    private static final long nodeId = 0L;
    private static final AccountID account11 =
            AccountID.newBuilder().accountNum(11).build();
    private static final AccountID account22 =
            AccountID.newBuilder().accountNum(22).build();
    private static final NodeInfo selfNodeInfo =
            new NodeInfoImpl(nodeId, account11, 0, new ArrayList<>(), null, new ArrayList<>(), false, null);

    @Test
    @DisplayName("Set self node accountId in memory works")
    void setSelfNodeAccountIdWorks() {
        subject = new SelfNodeAccountIdManagerImpl(configProvider, networkInfo, state);
        subject.setSelfNodeAccountId(account22);
        assertThat(subject.getSelfNodeAccountId()).isEqualTo(account22);
    }

    @Test
    @DisplayName("Init self node accountId from node details file works")
    void initSelfAccountIdFromFile102Works() {
        setup();
        mockNodeDetailsFile(nodeId, account22);
        subject.getSelfNodeAccountId();
        assertThat(subject.getSelfNodeAccountId()).isEqualTo(account22);
    }

    @Test
    @DisplayName("Use network info if node details file is empty")
    void initSelfAccountIdFromNetworkInfoWorks() {
        setup();
        mockEmptyNodeDetailsFile();
        subject.getSelfNodeAccountId();
        assertThat(subject.getSelfNodeAccountId()).isEqualTo(account11);
    }

    @Test
    @DisplayName("Use network info on parse error")
    void file102ParseError() {
        setup();
        mockNodeDetailsFile(Bytes.wrap("unparsable"));
        subject.getSelfNodeAccountId();
        assertThat(subject.getSelfNodeAccountId()).isEqualTo(account11);
    }

    @Test
    @DisplayName("Use network info if node is not found in node details file")
    void nodeIsNotInFile102() {
        setup();
        mockNodeDetailsFile(99, account22);
        subject.getSelfNodeAccountId();
        assertThat(subject.getSelfNodeAccountId()).isEqualTo(account11);
    }

    private void setup() {
        // mock config
        given(configProvider.getConfiguration()).willReturn(config);
        given(config.getConfigData(FilesConfig.class)).willReturn(filesConfig);
        given(config.getConfigData(HederaConfig.class)).willReturn(hederaConfig);
        given(filesConfig.nodeDetails()).willReturn(102L);

        // mock network info
        given(networkInfo.selfNodeInfo()).willReturn(selfNodeInfo);

        // mock state
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.getSingleton(ENTITY_ID_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> EntityNumber.newBuilder()
                                .build()));

        subject = new SelfNodeAccountIdManagerImpl(configProvider, networkInfo, state);
    }

    private void mockNodeDetailsFile(long nodeId, AccountID accountId) {
        final var addressBook = NodeAddressBook.newBuilder()
                .nodeAddress(NodeAddress.newBuilder()
                        .nodeId(nodeId)
                        .nodeAccountId(accountId)
                        .build())
                .build();
        mockNodeDetailsFile(NodeAddressBook.PROTOBUF.toBytes(addressBook));
    }

    private void mockNodeDetailsFile(Bytes bytes) {
        given(readableStates.get(FILES_STATE_ID))
                .willReturn(MapReadableKVState.builder(FILES_STATE_ID, FILES_STATE_LABEL)
                        .value(
                                FileID.newBuilder().fileNum(102).build(),
                                File.newBuilder().contents(bytes).build())
                        .build());
    }

    private void mockEmptyNodeDetailsFile() {
        given(readableStates.get(FILES_STATE_ID))
                .willReturn(MapReadableKVState.builder(FILES_STATE_ID, FILES_STATE_LABEL)
                        .value(
                                FileID.newBuilder().fileNum(102).build(),
                                File.newBuilder().contents(Bytes.EMPTY).build())
                        .build());
    }
}
