// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

public class TestArgumentUtils {
    /** A *static* pseudo-random number generator used to generate the legal identifiers */
    private static final Random RAND = new Random(8892381L);

    public static Stream<Arguments> illegalIdentifiers() {
        // The only valid characters are A-Za-z0-9_-. Any other character is a problem.
        // So I will construct three different types of invalid strings. Those that contain only
        // an invalid character, those that start with an invalid character and are followed by
        // valid characters, and those that start with valid characters and are trailed by a single
        // invalid character. I will select invalid characters from the ASCII set, and a subset of
        // values that are above the ASCII range. I will make sure to include multibyte characters.

        // Add every ASCII char that is illegal
        final List<String> illegalChars = new ArrayList<>();
        for (char i = 0; i < 255; i++) {
            if ((i >= 'A' && i <= 'Z') || (i >= 'a' && i <= 'z') || (i >= '0' && i <= '9') || (i == '-' || i == '_')) {
                // This is a valid character, so skip it -- don't add it to illegalChars!
                continue;
            }
            illegalChars.add("" + i);
        }
        // And the illegal 😈 multibyte character
        illegalChars.add("\uD83D\uDE08");

        // Construct the arguments
        final List<Arguments> argumentsList = new LinkedList<>();

        // Add each illegal character on its own as a test case
        for (final var illegalChar : illegalChars) {
            argumentsList.add(Arguments.of(illegalChar));
        }

        // Add each illegal character as the suffix to an otherwise legal string
        for (final var illegalChar : illegalChars) {
            argumentsList.add(Arguments.of("Some Legal Characters " + illegalChar));
        }

        // Add each illegal character as the prefix to an otherwise legal string
        for (final var illegalChar : illegalChars) {
            argumentsList.add(Arguments.of(illegalChar + " Some Legal Characters"));
        }

        // return it
        return argumentsList.stream();
    }

    public static Stream<Arguments> legalIdentifiers() {
        List<Arguments> args = new LinkedList<>();

        // An arbitrary collection of strings that contain 0-9A-Za-z and space.
        final String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        final String lower = upper.toLowerCase(Locale.ROOT);
        String digits = "0123456789";
        String validChars = upper + lower + digits + "-" + "_";

        // Test scenarios where there is a single valid char
        for (int i = 0; i < validChars.length(); i++) {
            args.add(Arguments.of("" + validChars.charAt(i)));
        }

        // Test scenarios where we have a set of valid chars
        for (int i = 0; i < 100; i++) {
            args.add(Arguments.of(TestBase.randomString(RAND, validChars, 12)));
        }

        return args.stream();
    }
}
