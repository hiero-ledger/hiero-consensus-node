// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.provider.Arguments;

@DisplayName("FCMTree Performance Tests")
class MerkleBinaryTreePerformanceTests extends MerkleBinaryTreeTests {

    @Override
    protected Stream<Arguments> buildSizeArguments() {
        final List<Arguments> arguments = new ArrayList<>();
        arguments.add(Arguments.of(100_000));
        return arguments.stream();
    }
}
