// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.extensions.ExtensionUtils.hapiTestMethodOf;

import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class SpecNamingExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(@NonNull final ExtensionContext extensionContext) {
        hapiTestMethodOf(extensionContext).ifPresent(method -> {
            final var specLabel = extensionContext.getRequiredTestClass().getSimpleName() + "." + method.getName();
            HapiSpec.SPEC_NAME.set(specLabel);
            HapiSpec.DISPLAY_NAME.set(extensionContext.getDisplayName());
        });
    }
}
