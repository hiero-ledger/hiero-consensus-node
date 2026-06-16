// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static org.hiero.base.file.FileUtils.getAbsolutePath;

import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.IssBlockUploadConfig;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;

/**
 * Dagger bindings for the ISS block-upload pipeline: the {@link BlockUploader} backend, chosen from
 * configuration (or a no-op when disabled/unsupported). The {@code IssBlockUploadCoordinator} is bound implicitly via
 * its {@code @Inject} constructor.
 */
@Module
public interface IssBlockUploadModule {

    @Provides
    @Singleton
    static BlockUploader provideBlockUploader(
            @NonNull final ConfigProvider configProvider,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager) {
        final var config = configProvider.getConfiguration().getConfigData(IssBlockUploadConfig.class);
        if (!config.enabled()) {
            return new NoOpBlockUploader();
        }
        final String nodeAccountString = asAccountString(selfNodeAccountIdManager.getSelfNodeAccountId());
        final Path credentialsFile =
                getAbsolutePath(config.credentialsFileDir()).resolve(config.credentialsFileName());
        return switch (config.backend()) {
            case BUCKY -> new BuckyBlockUploader(config, nodeAccountString, credentialsFile);
            case GCLOUD_CLI, HTTP -> {
                LogManager.getLogger(IssBlockUploadModule.class)
                        .warn(
                                "ISS block upload backend {} is not implemented; uploads are disabled (use BUCKY)",
                                config.backend());
                yield new NoOpBlockUploader();
            }
        };
    }
}
