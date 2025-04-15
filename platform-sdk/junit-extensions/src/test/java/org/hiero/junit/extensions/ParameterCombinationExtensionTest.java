// SPDX-License-Identifier: Apache-2.0
package org.hiero.junit.extensions;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

class ParameterCombinationExtensionTest {

    static Iterable<String> usernameSource() {
        return List.of("alice", "bob", "carol", "dave");
    }

    static Stream<Integer> ageSource() {
        return IntStream.of(66, 57, 35, 33, 16).boxed();
    }

    static Set<String> lastName() {
        return Set.of("doe");
    }

    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(param = "username", method = "usernameSource"),
        @ParamSource(param = "age", method = "ageSource"), // unsorted on purpose
        @ParamSource(param = "lastName", method = "lastName")
    })
    void testUser(
            @ParamName("username") String username, @ParamName("lastName") String lastName, @ParamName("age") int age) {
        // This method will be executed for all combinations of usernames and ages.
    }
}
