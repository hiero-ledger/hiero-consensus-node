// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.EthereumVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify.EthereumVerifyBundleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify.EthereumVerifyConfigTranslator;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Provides the {@link CallTranslator} implementations for the Ethereum verifier system contract.
 */
@Module
public interface EthereumVerifierTranslatorsModule {
    @Provides
    @Singleton
    @Named("EthereumVerifierTranslators")
    static List<CallTranslator<EthereumVerifierCallAttempt>> provideCallAttemptTranslators(
            @NonNull @Named("EthereumVerifierTranslators")
                    final Set<CallTranslator<EthereumVerifierCallAttempt>> translators) {
        return List.copyOf(translators);
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("EthereumVerifierTranslators")
    static CallTranslator<EthereumVerifierCallAttempt> provideVerifyConfigTranslator(
            @NonNull final EthereumVerifyConfigTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("EthereumVerifierTranslators")
    static CallTranslator<EthereumVerifierCallAttempt> provideVerifyBundleTranslator(
            @NonNull final EthereumVerifyBundleTranslator translator) {
        return translator;
    }
}
