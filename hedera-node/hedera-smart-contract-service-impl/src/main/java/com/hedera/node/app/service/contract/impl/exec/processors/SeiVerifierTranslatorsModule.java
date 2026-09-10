// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.SeiVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.verify.SeiVerifyBundleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.verify.SeiVerifyConfigTranslator;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Provides the {@link CallTranslator} implementations for the Sei verifier system contract.
 */
@Module
public interface SeiVerifierTranslatorsModule {
    @Provides
    @Singleton
    @Named("SeiVerifierTranslators")
    static List<CallTranslator<SeiVerifierCallAttempt>> provideCallAttemptTranslators(
            @NonNull @Named("SeiVerifierTranslators") final Set<CallTranslator<SeiVerifierCallAttempt>> translators) {
        return List.copyOf(translators);
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("SeiVerifierTranslators")
    static CallTranslator<SeiVerifierCallAttempt> provideVerifyConfigTranslator(
            @NonNull final SeiVerifyConfigTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("SeiVerifierTranslators")
    static CallTranslator<SeiVerifierCallAttempt> provideVerifyBundleTranslator(
            @NonNull final SeiVerifyBundleTranslator translator) {
        return translator;
    }
}
