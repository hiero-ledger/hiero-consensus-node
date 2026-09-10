// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.EthereumVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify.EthereumVerifyBundleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify.EthereumVerifyConfigTranslator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EthereumVerifierTranslatorsModuleTest {
    @Test
    void providesImmutableTranslatorList() {
        @SuppressWarnings("unchecked")
        final CallTranslator<EthereumVerifierCallAttempt> translator = mock(CallTranslator.class);

        final var provided = EthereumVerifierTranslatorsModule.provideCallAttemptTranslators(Set.of(translator));

        assertThat(provided).containsExactly(translator);
        assertThat(provided).isUnmodifiable();
    }

    @Test
    void providesSpecificTranslators() {
        final var configTranslator = mock(EthereumVerifyConfigTranslator.class);
        final var bundleTranslator = mock(EthereumVerifyBundleTranslator.class);

        assertThat(EthereumVerifierTranslatorsModule.provideVerifyConfigTranslator(configTranslator))
                .isSameAs(configTranslator);
        assertThat(EthereumVerifierTranslatorsModule.provideVerifyBundleTranslator(bundleTranslator))
                .isSameAs(bundleTranslator);
    }
}
