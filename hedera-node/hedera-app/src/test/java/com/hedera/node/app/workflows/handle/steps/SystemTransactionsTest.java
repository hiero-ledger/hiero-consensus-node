// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.parseFeeSchedules;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;
import static org.mockito.Mockito.times;

import com.hedera.hapi.node.base.CurrentAndNextFeeSchedule;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServicesConfigurationList;
import com.hedera.hapi.node.base.Setting;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
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
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.handle.DispatchProcessor;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @Mock
    private SemanticVersion softwareVersionFactory;

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
                recordCache,
                softwareVersionFactory);
    }

    @Test
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
        given(dispatch.stack()).willReturn(stack);
        given(dispatch.config()).willReturn(config);
        given(dispatch.consensusNow()).willReturn(CONSENSUS_NOW);
        given(dispatch.handleContext()).willReturn(handleContext);
        given(handleContext.dispatch(any())).willReturn(streamBuilder);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableNodeStore.class)).willReturn(readableNodeStore);

        subject.doPostUpgradeSetup(dispatch);

        final var filesConfig = config.getConfigData(FilesConfig.class);
        verify(fileService).updateAddressBookAndNodeDetailsAfterFreeze(any(SystemContext.class), eq(readableNodeStore));
        verifyUpdateDispatch(filesConfig.networkProperties(), serializedPropertyOverrides());
        verifyUpdateDispatch(filesConfig.hapiPermissions(), serializedPermissionOverrides());
        verifyUpdateDispatch(filesConfig.throttleDefinitions(), serializedThrottleOverrides());
        verifyUpdateDispatch(filesConfig.feeSchedules(), serializedFeeSchedules());
        verify(stack, times(5)).commitFullStack();
    }

    @Test
    void onlyAddressBookAndNodeDetailsAutoUpdateIsDispatchedWithNoFilesAvailable() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("networkAdmin.upgradeSysFilesLoc", tempDir.toString())
                .withValue("nodes.enableDAB", true)
                .getOrCreateConfig();
        given(dispatch.stack()).willReturn(stack);
        given(dispatch.config()).willReturn(config);
        given(dispatch.handleContext()).willReturn(handleContext);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableNodeStore.class)).willReturn(readableNodeStore);

        subject.doPostUpgradeSetup(dispatch);

        verify(fileService).updateAddressBookAndNodeDetailsAfterFreeze(any(SystemContext.class), eq(readableNodeStore));
        verify(stack, times(1)).commitFullStack();

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
        given(dispatch.stack()).willReturn(stack);
        given(dispatch.config()).willReturn(config);
        given(dispatch.handleContext()).willReturn(handleContext);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableNodeStore.class)).willReturn(readableNodeStore);

        subject.doPostUpgradeSetup(dispatch);

        verify(fileService).updateAddressBookAndNodeDetailsAfterFreeze(any(SystemContext.class), eq(readableNodeStore));
        verify(stack, times(1)).commitFullStack();

        final var errorLogs = logCaptor.errorLogs();
        assertThat(errorLogs.size()).isEqualTo(4);
        assertThat(errorLogs.getFirst()).startsWith("Failed to parse update file at");
        assertThat(errorLogs.get(1)).startsWith("Failed to parse update file at");
        assertThat(errorLogs.get(2)).startsWith("Failed to parse update file at");
        assertThat(errorLogs.getLast()).startsWith("Failed to parse update file at");
    }

    @SuppressWarnings("unchecked")
    private void verifyUpdateDispatch(final long fileNum, final Bytes contents) {
        verify(handleContext).dispatch(argThat(options -> {
            final var fileUpdate = options.body().fileUpdateOrThrow();
            return fileUpdate.fileIDOrThrow().fileNum() == fileNum
                    && fileUpdate.contents().equals(contents);
        }));
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

    private Bytes serializedFeeSchedules() {
        return CurrentAndNextFeeSchedule.PROTOBUF.toBytes(
                parseFeeSchedules(validFeeScheduleOverrides().getBytes(StandardCharsets.UTF_8)));
    }

    private Bytes serializedThrottleOverrides() {
        return Bytes.wrap(V0490FileSchema.parseThrottleDefinitions(validThrottleOverrides()));
    }

    private Bytes serializedPropertyOverrides() {
        return ServicesConfigurationList.PROTOBUF.toBytes(ServicesConfigurationList.newBuilder()
                .nameValue(Setting.newBuilder()
                        .name("tokens.nfts.maxBatchSizeMint")
                        .value("2")
                        .build())
                .build());
    }

    private Bytes serializedPermissionOverrides() {
        return ServicesConfigurationList.PROTOBUF.toBytes(ServicesConfigurationList.newBuilder()
                .nameValue(Setting.newBuilder().name("tokenMint").value("0-1").build())
                .build());
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
