// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the {@link BlockUploader} selection in {@link IssBlockUploadModule#provideBlockUploader}: both upload paths
 * disabled falls back to {@link NoOpBlockUploader}; either path enabled yields the Bucky-backed {@link
 * BuckyBlockUploader}.
 */
@ExtendWith(MockitoExtension.class)
class IssBlockUploadModuleTest {

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfiguration;

    @Mock
    private FailureBlockUploadConfig config;

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    @BeforeEach
    void setUp() {
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        lenient()
                .when(versionedConfiguration.getConfigData(FailureBlockUploadConfig.class))
                .thenReturn(config);
    }

    @Test
    void bothPathsDisabledYieldsNoOpUploader() {
        when(config.issBlockUploadEnabled()).thenReturn(false);
        when(config.triageUploadEnabled()).thenReturn(false);

        assertThat(IssBlockUploadModule.provideBlockUploader(configProvider, selfNodeAccountIdManager))
                .isInstanceOf(NoOpBlockUploader.class);
    }

    @Test
    void detectionEnabledYieldsBuckyUploader() {
        givenCredentials();
        when(config.issBlockUploadEnabled()).thenReturn(true);

        assertThat(IssBlockUploadModule.provideBlockUploader(configProvider, selfNodeAccountIdManager))
                .isInstanceOf(BuckyBlockUploader.class);
    }

    @Test
    void triageEnabledYieldsBuckyUploader() {
        givenCredentials();
        when(config.issBlockUploadEnabled()).thenReturn(false);
        when(config.triageUploadEnabled()).thenReturn(true);

        assertThat(IssBlockUploadModule.provideBlockUploader(configProvider, selfNodeAccountIdManager))
                .isInstanceOf(BuckyBlockUploader.class);
    }

    private void givenCredentials() {
        when(config.credentialsFileDir()).thenReturn("data/config");
        when(config.credentialsFileName()).thenReturn("iss-bucket-credentials.properties");
        when(selfNodeAccountIdManager.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder().accountNum(3).build());
    }
}
