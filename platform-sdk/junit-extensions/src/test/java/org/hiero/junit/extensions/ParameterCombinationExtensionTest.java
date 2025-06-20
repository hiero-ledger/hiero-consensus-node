// SPDX-License-Identifier: Apache-2.0
package org.hiero.junit.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

class ParameterCombinationExtensionTest {

    private static List<UsedParams> invokedParameters;

    static Iterable<String> usernameSource() {
        return List.of("alice", "bob", "carol");
    }

    static Stream<Integer> ageSource() {
        return IntStream.of(66, 57, 35).boxed();
    }

    static Set<String> lastName() {
        return Set.of("doe");
    }

    @BeforeAll
    static void beforeAll() {
        invokedParameters = new ArrayList<>();
    }

    @AfterAll
    static void afterAll() {
        Assertions.assertEquals(9, invokedParameters.size());
        Assertions.assertTrue(invokedParameters.contains(new UsedParams("alice", "doe", 66)));
        Assertions.assertTrue(invokedParameters.contains(new UsedParams("bob", "doe", 66)));
        Assertions.assertTrue(invokedParameters.contains(new UsedParams("carol", "doe", 66)));
        Assertions.assertTrue(invokedParameters.contains(new UsedParams("alice", "doe", 57)));
        Assertions.assertTrue(invokedParameters.contains(new UsedParams("bob", "doe", 57)));
        Assertions.assertTrue(invokedParameters.contains(new UsedParams("carol", "doe", 57)));
        Assertions.assertTrue(invokedParameters.contains(new UsedParams("alice", "doe", 35)));
        Assertions.assertTrue(invokedParameters.contains(new UsedParams("bob", "doe", 35)));
        Assertions.assertTrue(invokedParameters.contains(new UsedParams("carol", "doe", 35)));
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
        invokedParameters.add(new UsedParams(username, lastName, age));
    }

    record UsedParams(String username, String lastName, int age) {}
}
