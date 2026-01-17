// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util.junit;

import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import java.io.IOException;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class StateResolver implements ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == DeserializedSignedState.class;
    }

    @Override
    public DeserializedSignedState resolveParameter(
            ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        try {
            return StateUtils.getDeserializedSignedState();
        } catch (ConstructableRegistryException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
