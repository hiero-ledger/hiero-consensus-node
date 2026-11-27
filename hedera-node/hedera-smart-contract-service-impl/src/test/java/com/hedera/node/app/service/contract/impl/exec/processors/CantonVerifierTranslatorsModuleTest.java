// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.CantonVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.verify.CantonVerifyBundleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.verify.CantonVerifyConfigTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CantonVerifierTranslatorsModuleTest {
    @Test
    void providesImmutableTranslatorList() {
        @SuppressWarnings("unchecked")
        final CallTranslator<CantonVerifierCallAttempt> translator = mock(CallTranslator.class);

        final var provided = CantonVerifierTranslatorsModule.provideCallAttemptTranslators(Set.of(translator));

        assertThat(provided).containsExactly(translator);
        assertThat(provided).isUnmodifiable();
    }

    @Test
    void providesSpecificTranslators() {
        final var configTranslator = mock(CantonVerifyConfigTranslator.class);
        final var bundleTranslator = mock(CantonVerifyBundleTranslator.class);

        assertThat(CantonVerifierTranslatorsModule.provideVerifyConfigTranslator(configTranslator))
                .isSameAs(configTranslator);
        assertThat(CantonVerifierTranslatorsModule.provideVerifyBundleTranslator(bundleTranslator))
                .isSameAs(bundleTranslator);
    }
}
