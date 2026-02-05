// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util.junit;

import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.state.merkle.VirtualMapState;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Parameter resolver for {@link VirtualMapState} in JUnit tests.
 */
public class MerkleNodeStateResolver implements ParameterResolver {
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == VirtualMapState.class;
    }

    @Override
    public VirtualMapState resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return StateUtils.getDefaultState();
    }
}
