// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.SeiVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.verify.SeiVerifyBundleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.verify.SeiVerifyConfigTranslator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SeiVerifierTranslatorsModuleTest {
    @Test
    void providesImmutableTranslatorList() {
        @SuppressWarnings("unchecked")
        final CallTranslator<SeiVerifierCallAttempt> translator = mock(CallTranslator.class);

        final var provided = SeiVerifierTranslatorsModule.provideCallAttemptTranslators(Set.of(translator));

        assertThat(provided).containsExactly(translator);
        assertThat(provided).isUnmodifiable();
    }

    @Test
    void providesSpecificTranslators() {
        final var configTranslator = mock(SeiVerifyConfigTranslator.class);
        final var bundleTranslator = mock(SeiVerifyBundleTranslator.class);

        assertThat(SeiVerifierTranslatorsModule.provideVerifyConfigTranslator(configTranslator))
                .isSameAs(configTranslator);
        assertThat(SeiVerifierTranslatorsModule.provideVerifyBundleTranslator(bundleTranslator))
                .isSameAs(bundleTranslator);
    }
}
