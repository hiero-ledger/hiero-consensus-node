// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Assertions;

public class QueryLogValidator {
    private final String logFileLocation;

    public QueryLogValidator(final String logFileLocation) {
        this.logFileLocation = logFileLocation;
    }

    public static void main(String... args) throws IOException {
        final var subject = new QueryLogValidator("build/network/itest/output/node_0/queries.log");

        subject.validate();
    }

    public void validate() throws IOException {
        final List<String> problemLines = Files.readAllLines(Paths.get(logFileLocation));
        if (!problemLines.isEmpty()) {
            Assertions.fail(
                    "Found problems in log file '" + logFileLocation + "':\n" + String.join("\n", problemLines));
        }
    }
}
