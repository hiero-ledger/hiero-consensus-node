// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.parameterresolver;

import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

public class VirtualMapAndDataSourceProvider implements ArgumentsProvider {
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
        return VirtualMapHolder.getInstance().getRecords().stream().map(Arguments::of);
    }
}
