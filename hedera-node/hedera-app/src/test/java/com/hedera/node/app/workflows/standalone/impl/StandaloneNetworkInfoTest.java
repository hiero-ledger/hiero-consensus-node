// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone.impl;

import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_LABEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.NodeAddress;
import com.hedera.hapi.node.base.NodeAddressBook;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StandaloneNetworkInfoTest {

    private static final long NODE_DETAILS_FILE_NUM = 102L;
    private static final FileID NODE_DETAILS_FILE_ID =
            FileID.newBuilder().fileNum(NODE_DETAILS_FILE_NUM).build();

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration config;

    @Mock
    private FilesConfig filesConfig;

    @Mock
    private HederaConfig hederaConfig;

    @Mock
    private LedgerConfig ledgerConfig;

    @Mock
    private State state;

    @Mock
    private ReadableStates readableStates;

    private StandaloneNetworkInfo subject;

    @BeforeEach
    void setUp() {
        given(configProvider.getConfiguration()).willReturn(config);
        given(config.getConfigData(LedgerConfig.class)).willReturn(ledgerConfig);
        given(ledgerConfig.id()).willReturn(Bytes.EMPTY);
        subject = new StandaloneNetworkInfo(configProvider);
    }

    @Test
    void initFrom_withValidNodeDetails_populatesAddressBook() {
        final var account1 = AccountID.newBuilder().accountNum(3).build();
        final var account2 = AccountID.newBuilder().accountNum(4).build();
        final var addressBook = NodeAddressBook.newBuilder()
                .nodeAddress(
                        NodeAddress.newBuilder()
                                .nodeId(0)
                                .nodeAccountId(account1)
                                .build(),
                        NodeAddress.newBuilder()
                                .nodeId(1)
                                .nodeAccountId(account2)
                                .build())
                .build();
        givenNodeDetailsFile(NodeAddressBook.PROTOBUF.toBytes(addressBook));

        subject.initFrom(state);

        assertThat(subject.addressBook()).hasSize(2);
        assertThat(subject.containsNode(0)).isTrue();
        assertThat(subject.containsNode(1)).isTrue();
        assertThat(subject.nodeInfo(0).accountId()).isEqualTo(account1);
        assertThat(subject.nodeInfo(1).accountId()).isEqualTo(account2);
    }

    @Test
    void initFrom_withInvalidBytes_setsEmptyAddressBook() {
        givenNodeDetailsFile(Bytes.wrap(new byte[] {0x06}));

        subject.initFrom(state);

        assertThat(subject.addressBook()).isEmpty();
    }

    @Test
    void initFrom_withEmptyBytes_setsEmptyAddressBook() {
        givenNodeDetailsFile(Bytes.EMPTY);

        subject.initFrom(state);

        assertThat(subject.addressBook()).isEmpty();
    }

    private void givenNodeDetailsFile(final Bytes contents) {
        given(config.getConfigData(FilesConfig.class)).willReturn(filesConfig);
        given(config.getConfigData(HederaConfig.class)).willReturn(hederaConfig);
        given(filesConfig.nodeDetails()).willReturn(NODE_DETAILS_FILE_NUM);
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.get(FILES_STATE_ID))
                .willReturn(MapReadableKVState.builder(FILES_STATE_ID, FILES_STATE_LABEL)
                        .value(
                                NODE_DETAILS_FILE_ID,
                                File.newBuilder().contents(contents).build())
                        .build());
    }
}
