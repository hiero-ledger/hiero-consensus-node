// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import static org.assertj.core.api.Assertions.assertThat;

public final class ExceptionMsgUtils {

    private ExceptionMsgUtils() {
        // Utility class
    }

    public static void assertMissingRequiredParamMsg(final Exception exception, final String paramName) {
        assertThat(exception.getMessage())
                .containsIgnoringCase("Missing required parameter")
                .contains("'<" + paramName + ">'");
    }

    public static void assertInvalidOptionMsg(final Exception exception, final String optionName) {
        assertThat(exception.getMessage())
                .containsIgnoringCase("Invalid value for option")
                .contains("'<" + optionName + ">'");
    }

    public static void assertUnknownOptionMsg(final Exception exception, final String optionName) {
        assertThat(exception.getMessage())
                .containsIgnoringCase("Unknown option")
                .contains("'" + optionName + "'");
    }
}
