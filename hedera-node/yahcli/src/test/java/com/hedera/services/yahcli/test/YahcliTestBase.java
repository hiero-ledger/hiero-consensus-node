// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import com.hedera.services.yahcli.Yahcli;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import picocli.CommandLine;

public class YahcliTestBase {
    // (FUTURE) Wrap System.out and System.err to capture _and display_ outputs
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    private Yahcli yahcli;
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        // Reset the test output streams
        outContent.reset();
        errContent.reset();

        // Modify the output streams to capture System.out and System.err
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        // Instantiate the `Yahcli` and `CommandLine` instances
        yahcli = new Yahcli();
        commandLine = new CommandLine(yahcli);
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    protected CommandLine.ParseResult parseArgs(final String args) {
        return commandLine.parseArgs(args.split(" "));
    }

    protected Yahcli testSubjectCli() {
        return yahcli;
    }
}
