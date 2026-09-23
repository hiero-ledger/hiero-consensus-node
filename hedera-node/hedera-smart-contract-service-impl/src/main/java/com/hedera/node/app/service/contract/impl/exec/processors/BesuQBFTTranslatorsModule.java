// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.BesuQBFTVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify.BesuQBFTVerifyBundleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify.BesuQBFTVerifyConfigTranslator;
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
 * Provides the {@link CallTranslator} implementations for the Besu QBFT verifier system contract.
 */
@Module
public interface BesuQBFTTranslatorsModule {
    @Provides
    @Singleton
    @Named("BesuQBFTVerifierTranslators")
    static List<CallTranslator<BesuQBFTVerifierCallAttempt>> provideCallAttemptTranslators(
            @NonNull @Named("BesuQBFTVerifierTranslators")
                    final Set<CallTranslator<BesuQBFTVerifierCallAttempt>> translators) {
        return List.copyOf(translators);
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("BesuQBFTVerifierTranslators")
    static CallTranslator<BesuQBFTVerifierCallAttempt> provideVerifyConfigTranslator(
            @NonNull final BesuQBFTVerifyConfigTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("BesuQBFTVerifierTranslators")
    static CallTranslator<BesuQBFTVerifierCallAttempt> provideVerifyBundleTranslator(
            @NonNull final BesuQBFTVerifyBundleTranslator translator) {
        return translator;
    }
}
