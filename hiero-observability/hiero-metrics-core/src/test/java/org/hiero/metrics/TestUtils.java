// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

public final class TestUtils {

    private TestUtils() {}

    public static String[] invalidNames() {
        return new String[] {
            "", // empty
            " ", // blank
            "123invalid", // starts with digits
            "9test", // starts with digit
            "0metric", // starts with digit
            "invalid-name", // contains hyphen
            "invalid.name", // contains dot
            "invalid name", // contains space
            "invalid@name", // contains @
            "invalid#name", // contains #
            "invalid$name", // contains $
            "invalid%name", // contains %
            "invalid&name", // contains &
            "invalid*name", // contains *
            "invalid+name", // contains +
            "invalid=name", // contains =
            "invalid!name", // contains !
            "invalid?name", // contains ?
            "invalid/name", // contains /
            "invalid\\name", // contains backslash
            "invalid|name", // contains pipe
            "invalid<name", // contains <
            "invalid>name", // contains >
            "invalid,name", // contains comma
            "invalid;name", // contains semicolon
            "invalid:name", // contains colon
            "invalid\"name", // contains quote
            "invalid'name", // contains apostrophe
            "invalid[name", // contains [
            "invalid]name", // contains ]
            "invalid{name", // contains {
            "invalid}name", // contains }
            "invalid(name", // contains (
            "invalid)name", // contains )
            "invalid~name", // contains ~
            "invalid`name", // contains backtick
        };
    }
}
