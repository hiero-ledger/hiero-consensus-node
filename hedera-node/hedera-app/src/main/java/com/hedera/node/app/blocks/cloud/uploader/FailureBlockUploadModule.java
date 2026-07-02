// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static org.hiero.base.file.FileUtils.getAbsolutePath;

import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import javax.inject.Singleton;

/**
 * Dagger bindings for the failure block-upload pipeline: the (Bucky-backed) {@link BlockUploader}, or a no-op when both
 * upload paths are disabled. The {@code TriageBlockUploadCoordinator} and {@code IssDetectionUploadCoordinator} are
 * bound implicitly via their {@code @Inject} constructors.
 */
@Module
public interface FailureBlockUploadModule {

    @Provides
    @Singleton
    static BlockUploader provideBlockUploader(
            @NonNull final ConfigProvider configProvider,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager) {
        final var config = configProvider.getConfiguration().getConfigData(FailureBlockUploadConfig.class);
        if (!config.issBlockUploadEnabled() && !config.triageUploadEnabled()) {
            return new NoOpBlockUploader();
        }
        final String nodeAccountString = asAccountString(selfNodeAccountIdManager.getSelfNodeAccountId());
        final Path credentialsFile =
                getAbsolutePath(config.credentialsFileDir()).resolve(config.credentialsFileName());
        return new BuckyBlockUploader(config, nodeAccountString, credentialsFile);
    }
}
