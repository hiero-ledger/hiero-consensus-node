// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public class ParsingUtils {
    public static <T, A, B> T fromTwoPartDelimited(
            final String literal,
            final String delimiter,
            final BiConsumer<A, B> validator,
            final Function<String, A> aParser,
            final Function<String, B> bParser,
            final BiFunction<A, B, T> finisher) {
        final var splitIndex = literal.indexOf(delimiter);
        if (splitIndex == -1) {
            throw new IllegalArgumentException("Missing '" + delimiter + "' in literal '" + literal + "'");
        }
        final var delimLen = delimiter.length();
        final var a = aParser.apply(literal.substring(0, splitIndex));
        final var b = bParser.apply(literal.substring(splitIndex + delimLen));
        validator.accept(a, b);
        return finisher.apply(a, b);
    }

    private ParsingUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }
}
