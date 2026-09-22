// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.node.app.util.FileUtilities.createFileID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FakeGenesisState;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FacilityInitModuleTest {
    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private CongestionMultipliers congestionMultipliers;

    @Test
    void unparseableSimpleFeeScheduleAtInitializationFailsHard() {
        final var configProvider = new ConfigProviderImpl();
        final var config = configProvider.getConfiguration();

        // A fully-seeded post-genesis state, then overwrite the simple fee schedule file (0.0.113) with
        // bytes that cannot be parsed, so feeManager.updateSimpleFees(...) returns a non-SUCCESS status.
        final var state = FakeGenesisState.make(Map.of());
        overwriteFileWithUnparseableContents(
                state, config, config.getConfigData(FilesConfig.class).simpleFeesSchedules());

        final var feeManager = new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(), Set.of());
        final var fileService = new FileServiceImpl();

        // The restart path must fail hard on an unparseable simple fee schedule (as the genesis path already does),
        // rather than logging and continuing with undefined FeeManager state.
        assertThatThrownBy(
                        () -> FacilityInitModule.initializeFeeManager(state, fileService, configProvider, feeManager))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void overwriteFileWithUnparseableContents(
            final State state, final Configuration config, final long fileNum) {
        final var fileId = createFileID(fileNum, config);
        final var writableStates = state.getWritableStates(FileService.NAME);
        final var files = writableStates.<FileID, File>get(V0490FileSchema.FILES_STATE_ID);
        files.put(
                fileId,
                File.newBuilder()
                        .fileId(fileId)
                        .keys(KeyList.DEFAULT)
                        .contents(Bytes.wrap(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}))
                        .build());
        ((CommittableWritableStates) writableStates).commit();
    }
}
