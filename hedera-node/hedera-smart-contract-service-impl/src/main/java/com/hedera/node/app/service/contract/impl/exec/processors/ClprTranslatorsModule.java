// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.hapi.utils.blocks.NativeTssVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getchannel.GetChannelTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.getendpointmanifest.GetEndpointManifestTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.sendmessage.SendMessageTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify.VerifyBundleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify.VerifyConfigTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Provides the {@link CallTranslator} implementations for the CLPR system contract.
 */
@Module
public interface ClprTranslatorsModule {
    @Provides
    @Singleton
    @Named("ClprTranslators")
    static List<CallTranslator<ClprCallAttempt>> provideCallAttemptTranslators(
            @NonNull @Named("ClprTranslators") final Set<CallTranslator<ClprCallAttempt>> translators) {
        return List.copyOf(translators);
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("ClprTranslators")
    static CallTranslator<ClprCallAttempt> provideSendMessageTranslator(
            @NonNull final SendMessageTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("ClprTranslators")
    static CallTranslator<ClprCallAttempt> provideGetChannelTranslator(@NonNull final GetChannelTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("ClprTranslators")
    static CallTranslator<ClprCallAttempt> provideGetEndpointManifestTranslator(
            @NonNull final GetEndpointManifestTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("ClprTranslators")
    static CallTranslator<ClprCallAttempt> provideVerifyConfigTranslator(
            @NonNull final VerifyConfigTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("ClprTranslators")
    static CallTranslator<ClprCallAttempt> provideVerifyBundleTranslator(
            @NonNull final VerifyBundleTranslator translator) {
        return translator;
    }

    /**
     * Binds the native {@link TssVerifier} backed by
     * {com.hedera.cryptography.tss.TSS#verifyTSS(byte[], byte[], byte[])}. The composite
     * signature carries the peer ledger's hinTS verification key plus a WRAPS recursive proof
     * that binds it to {@code ledgerId}, so no separate per-ledger key registry is required.
     */
    @Provides
    @Singleton
    static TssVerifier provideTssVerifier() {
        return new NativeTssVerifier();
    }
}
