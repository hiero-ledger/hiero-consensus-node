// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.token.records.TokenContext;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.SystemContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.recordcache.BlockRecordSource;
import com.hedera.node.app.state.recordcache.LegacyListRecordSource;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.handle.DispatchProcessor;
import com.hedera.node.app.workflows.handle.HandleOutput;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.spi.fixtures.Scenarios.NODE_1;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class SystemTransactionsTest {
    private static final Instant CONSENSUS_NOW = Instant.parse("2023-08-10T00:00:00Z");

    @Mock(strictness = Mock.Strictness.LENIENT)
    private TokenContext context;

    @Mock
    private FileServiceImpl fileService;

    @Mock
    private SavepointStackImpl stack;

    @Mock
    private TransactionInfo transactionInfo;

    @Mock
    private HandleOutput output;

    @Mock
    private LegacyListRecordSource recordSource;

    @Mock
    private BlockRecordSource blockRecordSource;

    @Mock
    private State state;

    @Mock
    private ReadableStates readableStates;

    @Mock
    private ReadableSingletonState<?> singletonState;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ReadableNodeStore readableNodeStore;

    @Mock
    private HandleContext handleContext;

    @Mock
    private Dispatch dispatch;

    @Mock
    private StreamBuilder streamBuilder;

    @Mock
    private ParentTxnFactory parentTxnFactory;

    @Mock
    private ParentTxn parentTxn;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private DispatchProcessor dispatchProcessor;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private AppContext appContext;

    @Mock
    private EntityIdFactory idFactory;

    @Mock
    private BlockRecordManager blockRecordManager;

    @Mock
    private BlockStreamManager blockStreamManager;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private HederaRecordCache recordCache;

    @LoggingSubject
    private SystemTransactions subject;

    @LoggingTarget
    private LogCaptor logCaptor;

    @TempDir
    java.nio.file.Path tempDir;

    @BeforeEach
    void setup() {
        given(context.consensusTime()).willReturn(CONSENSUS_NOW);
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(DEFAULT_CONFIG, 1L));
        given(appContext.idFactory()).willReturn(idFactory);

        subject = new SystemTransactions(
                InitTrigger.GENESIS,
                parentTxnFactory,
                fileService,
                networkInfo,
                configProvider,
                dispatchProcessor,
                appContext,
                blockRecordManager,
                blockStreamManager,
                exchangeRateManager,
                recordCache
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulAutoUpdatesAreDispatchedWithFilesAvailable() throws IOException {
        final var config = HederaTestConfigBuilder.create()
                .withValue("networkAdmin.upgradeSysFilesLoc", tempDir.toString())
                .withValue("nodes.enableDAB", true)
                .getOrCreateConfig();
        final var adminConfig = config.getConfigData(NetworkAdminConfig.class);
        Files.writeString(tempDir.resolve(adminConfig.upgradePropertyOverridesFile()), validPropertyOverrides());
        Files.writeString(tempDir.resolve(adminConfig.upgradePermissionOverridesFile()), validPermissionOverrides());
        Files.writeString(tempDir.resolve(adminConfig.upgradeThrottlesFile()), validThrottleOverrides());
        Files.writeString(tempDir.resolve(adminConfig.upgradeFeeSchedulesFile()), validFeeScheduleOverrides());
        given(networkInfo.addressBook()).willReturn(List.of(NODE_1.asInfo()));
        given(state.getReadableStates(anyString())).willReturn(readableStates);
        given(readableStates.getSingleton(anyString())).willReturn((ReadableSingletonState<Object>) singletonState);
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));
        given(parentTxnFactory.createSystemTxn(any(), any(), any(), any(), any(), any())).willReturn(parentTxn);
        given(parentTxnFactory.createDispatch(any(), any(), any(), any())).willReturn(dispatch);
        given(dispatch.streamBuilder()).willReturn(streamBuilder);
        given(parentTxn.stack()).willReturn(stack);
        given(parentTxn.txnInfo()).willReturn(transactionInfo);
        given(transactionInfo.transactionID()).willReturn(TransactionID.DEFAULT);
        given(streamBuilder.status()).willReturn(SUCCESS);
        given(stack.buildHandleOutput(any(), any())).willReturn(output);
        given(output.preferringBlockRecordSource()).willReturn(recordSource);
        given(output.recordSourceOrThrow()).willReturn(recordSource);
        given(output.blockRecordSourceOrThrow()).willReturn(blockRecordSource);

        subject.doPostUpgradeSetup(CONSENSUS_NOW, state);

        verify(fileService).updateAddressBookAndNodeDetailsAfterFreeze(any(SystemContext.class), any(ReadableNodeStore.class));
        verify(dispatchProcessor, times(4)).processDispatch(dispatch);
    }

    @Test
    void onlyAddressBookAndNodeDetailsAutoUpdateIsDispatchedWithNoFilesAvailable() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("networkAdmin.upgradeSysFilesLoc", tempDir.toString())
                .withValue("nodes.enableDAB", true)
                .getOrCreateConfig();
        given(networkInfo.addressBook()).willReturn(List.of(NODE_1.asInfo()));
        given(state.getReadableStates(anyString())).willReturn(readableStates);
        given(readableStates.getSingleton(anyString())).willReturn((ReadableSingletonState<Object>) singletonState);
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));

        subject.doPostUpgradeSetup(CONSENSUS_NOW, state);

        verify(fileService).updateAddressBookAndNodeDetailsAfterFreeze(any(SystemContext.class), any(ReadableNodeStore.class));

        final var infoLogs = logCaptor.infoLogs();
        assertThat(infoLogs.size()).isEqualTo(5);
        assertThat(infoLogs.getFirst()).startsWith("No post-upgrade file for feeSchedules.json");
        assertThat(infoLogs.get(1)).startsWith("No post-upgrade file for throttles.json");
        assertThat(infoLogs.get(2)).startsWith("No post-upgrade file for application-override.properties");
        assertThat(infoLogs.get(3)).startsWith("No post-upgrade file for api-permission-override.properties");
        assertThat(infoLogs.getLast()).startsWith("No post-upgrade file for node-admin-keys.json");
    }

    @Test
    void onlyAddressBookAndNodeDetailsAutoUpdateIsDispatchedWithInvalidFilesAvailable() throws IOException {
        final var config = HederaTestConfigBuilder.create()
                .withValue("networkAdmin.upgradeSysFilesLoc", tempDir.toString())
                .withValue("nodes.enableDAB", true)
                .getOrCreateConfig();
        final var adminConfig = config.getConfigData(NetworkAdminConfig.class);
        Files.writeString(tempDir.resolve(adminConfig.upgradePropertyOverridesFile()), invalidPropertyOverrides());
        Files.writeString(tempDir.resolve(adminConfig.upgradePermissionOverridesFile()), invalidPermissionOverrides());
        Files.writeString(tempDir.resolve(adminConfig.upgradeThrottlesFile()), invalidThrottleOverrides());
        Files.writeString(tempDir.resolve(adminConfig.upgradeFeeSchedulesFile()), invalidFeeScheduleOverrides());
        given(networkInfo.addressBook()).willReturn(List.of(NODE_1.asInfo()));
        given(state.getReadableStates(anyString())).willReturn(readableStates);
        given(readableStates.getSingleton(anyString())).willReturn((ReadableSingletonState<Object>) singletonState);
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));

        subject.doPostUpgradeSetup(CONSENSUS_NOW, state);

        verify(fileService).updateAddressBookAndNodeDetailsAfterFreeze(any(SystemContext.class), any(ReadableNodeStore.class));

        final var errorLogs = logCaptor.errorLogs();
        assertThat(errorLogs.size()).isEqualTo(4);
        assertThat(errorLogs.getFirst()).startsWith("Failed to parse update file at");
        assertThat(errorLogs.get(1)).startsWith("Failed to parse update file at");
        assertThat(errorLogs.get(2)).startsWith("Failed to parse update file at");
        assertThat(errorLogs.getLast()).startsWith("Failed to parse update file at");
    }

    private String validPropertyOverrides() {
        return "tokens.nfts.maxBatchSizeMint=2";
    }

    private String validPermissionOverrides() {
        return "tokenMint=0-1";
    }

    private String validThrottleOverrides() {
        return """
{
  "buckets": [
    {
      "name": "ThroughputLimits",
      "burstPeriod": 1,
      "throttleGroups": [
        {
          "opsPerSec": 1,
          "operations": [ "TokenMint" ]
        }
      ]
    }
  ]
}""";
    }

    private String validFeeScheduleOverrides() {
        return """
[
  {
    "currentFeeSchedule": [
      {
        "expiryTime": 1630800000
      }
    ]
  },
  {
    "nextFeeSchedule": [
      {
        "expiryTime": 1633392000
      }
    ]
  }
]""";
    }

    private String invalidPropertyOverrides() {
        return "tokens.nfts.maxBatchSizeM\\u12G4";
    }

    private String invalidPermissionOverrides() {
        return "tokenM\\u12G4";
    }

    private String invalidThrottleOverrides() {
        return """
{{
  "buckets": [
    {
      "name": "ThroughputLimits",
      "burstPeriod": 1,
      "throttleGroups": [
        {
          "opsPerSec": 1,
          "operations": [ "TokenMint" ]
        }
      ]
    }
  ]
}""";
    }

    private String invalidFeeScheduleOverrides() {
        return """
[[
  {
    "currentFeeSchedule": [
      {
        "expiryTime": 1630800000
      }
    ]
  },
  {
    "nextFeeSchedule": [
      {
        "expiryTime": 1633392000
      }
    ]
  }
]""";
    }
}
