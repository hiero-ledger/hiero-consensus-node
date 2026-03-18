// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats;

import static com.hedera.node.app.records.RecordTestData.SIGNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordWriterV6;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import java.nio.file.FileSystems;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class BlockRecordFactoryImplTest extends AppTestBase {

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    @Test
    void createV6BasedOnConfig() throws Exception {
        when(selfNodeAccountIdManager.getSelfNodeAccountId()).thenReturn(AccountID.DEFAULT);
        final var app = appBuilder()
                .withConfigValue("hedera.recordStream.logDir", "hedera-node/data/recordStreams")
                .build();
        final var factory = new BlockRecordWriterFactoryImpl(
                app.configProvider(), SIGNER, FileSystems.getDefault(), selfNodeAccountIdManager);
        final var writer = factory.create();
        assertThat(writer).isInstanceOf(BlockRecordWriterV6.class);
    }

    @Test
    void createV7BasedOnConfigThrows() throws Exception {
        final var app = appBuilder()
                .withConfigValue("hedera.recordStream.recordFileVersion", 7)
                .withConfigValue("hedera.recordStream.logDir", "hedera-node/data/recordStreams")
                .build();
        final var factory = new BlockRecordWriterFactoryImpl(
                app.configProvider(), SIGNER, FileSystems.getDefault(), selfNodeAccountIdManager);
        assertThatThrownBy(factory::create)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Record file version 7 is not yet supported");
    }

    @Test
    void createUnknownVersionBasedOnConfigThrows() throws Exception {
        final var app = appBuilder()
                .withConfigValue("hedera.recordStream.recordFileVersion", 99999)
                .withConfigValue("hedera.recordStream.logDir", "hedera-node/data/recordStreams")
                .build();
        final var factory = new BlockRecordWriterFactoryImpl(
                app.configProvider(), SIGNER, FileSystems.getDefault(), selfNodeAccountIdManager);
        assertThatThrownBy(factory::create)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown record file version");
    }
}
