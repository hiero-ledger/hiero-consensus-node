// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.CantonVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.verify.CantonVerifyBundleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.verify.CantonVerifyConfigTranslator;
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
 * Provides the {@link CallTranslator} implementations for the Canton verifier system contract.
 */
@Module
public interface CantonVerifierTranslatorsModule {
    @Provides
    @Singleton
    @Named("CantonVerifierTranslators")
    static List<CallTranslator<CantonVerifierCallAttempt>> provideCallAttemptTranslators(
            @NonNull @Named("CantonVerifierTranslators")
                    final Set<CallTranslator<CantonVerifierCallAttempt>> translators) {
        return List.copyOf(translators);
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("CantonVerifierTranslators")
    static CallTranslator<CantonVerifierCallAttempt> provideVerifyConfigTranslator(
            @NonNull final CantonVerifyConfigTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("CantonVerifierTranslators")
    static CallTranslator<CantonVerifierCallAttempt> provideVerifyBundleTranslator(
            @NonNull final CantonVerifyBundleTranslator translator) {
        return translator;
    }
}
