// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.map;

import com.swirlds.merkle.test.fixtures.map.util.KeyValueProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.provider.Arguments;

@DisplayName("MerkleMap Performance Tests")
public class MerkleMapPerformanceTests extends MerkleMapTests {

    @Override
    protected Stream<Arguments> buildArguments() {
        final List<Arguments> arguments = new ArrayList<>();
        for (final KeyValueProvider keyValueProvider : KeyValueProvider.values()) {
            arguments.add(Arguments.of(0, keyValueProvider));
            arguments.add(Arguments.of(262_144, keyValueProvider));
        }

        return arguments.stream();
    }

    @Override
    protected Stream<Arguments> buildNumberOfModifications() {
        final List<Arguments> arguments = new ArrayList<>();
        arguments.add(Arguments.of(1));
        arguments.add(Arguments.of(5));
        arguments.add(Arguments.of(8));
        arguments.add(Arguments.of(10));
        return arguments.stream();
    }
}
