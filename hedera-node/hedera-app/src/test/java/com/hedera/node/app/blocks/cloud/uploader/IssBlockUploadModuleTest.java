// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.IssBlockUploadConfig;
import com.hedera.node.config.types.UploaderBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the {@link BlockUploader} selection in {@link IssBlockUploadModule#provideBlockUploader}: disabled and
 * not-yet-implemented backends fall back to {@link NoOpBlockUploader}; {@code BUCKY} yields {@link BuckyBlockUploader}.
 */
@ExtendWith(MockitoExtension.class)
class IssBlockUploadModuleTest {

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfiguration;

    @Mock
    private IssBlockUploadConfig config;

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    @BeforeEach
    void setUp() {
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        lenient()
                .when(versionedConfiguration.getConfigData(IssBlockUploadConfig.class))
                .thenReturn(config);
    }

    @Test
    void disabledYieldsNoOpUploader() {
        when(config.enabled()).thenReturn(false);

        assertThat(IssBlockUploadModule.provideBlockUploader(configProvider, selfNodeAccountIdManager))
                .isInstanceOf(NoOpBlockUploader.class);
    }

    @Test
    void buckyBackendYieldsBuckyUploader() {
        givenEnabledWith(UploaderBackend.BUCKY);

        assertThat(IssBlockUploadModule.provideBlockUploader(configProvider, selfNodeAccountIdManager))
                .isInstanceOf(BuckyBlockUploader.class);
    }

    @Test
    void gcloudCliBackendFallsBackToNoOp() {
        givenEnabledWith(UploaderBackend.GCLOUD_CLI);

        assertThat(IssBlockUploadModule.provideBlockUploader(configProvider, selfNodeAccountIdManager))
                .isInstanceOf(NoOpBlockUploader.class);
    }

    @Test
    void httpBackendFallsBackToNoOp() {
        givenEnabledWith(UploaderBackend.HTTP);

        assertThat(IssBlockUploadModule.provideBlockUploader(configProvider, selfNodeAccountIdManager))
                .isInstanceOf(NoOpBlockUploader.class);
    }

    private void givenEnabledWith(final UploaderBackend backend) {
        when(config.enabled()).thenReturn(true);
        when(config.backend()).thenReturn(backend);
        when(config.credentialsFileDir()).thenReturn("data/config");
        when(config.credentialsFileName()).thenReturn("iss-bucket-credentials.properties");
        when(selfNodeAccountIdManager.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder().accountNum(3).build());
    }
}
