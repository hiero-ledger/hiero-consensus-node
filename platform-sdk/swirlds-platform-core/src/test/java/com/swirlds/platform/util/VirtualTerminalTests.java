// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("VirtualTerminal Tests")
class VirtualTerminalTests {

    @Test
    @DisplayName("Successful Command With Output Test")
    void successfulCommandWithOutputTest() {
        final VirtualTerminal terminal = new VirtualTerminal();

        final CommandResult result = terminal.run("echo", "Hello World!");

        assertTrue(result.isSuccessful());
        assertEquals(0, result.exitCode());
        assertEquals("Hello World!\n", result.out());
        assertEquals("", result.error());
    }

    @Test
    @DisplayName("Successful Command Without Output Test")
    void successfulCommandWithoutOutputTest() {
        final VirtualTerminal terminal = new VirtualTerminal();

        assertFalse(Files.exists(Path.of("asdf")));

        final CommandResult result1 = terminal.run("touch", "asdf");

        assertTrue(result1.isSuccessful());
        assertEquals(0, result1.exitCode());
        assertEquals("", result1.out());
        assertEquals("", result1.error());

        assertTrue(Files.exists(Path.of("asdf")));

        final CommandResult result2 = terminal.run("rm", "asdf");

        assertTrue(result2.isSuccessful());
        assertEquals(0, result2.exitCode());
        assertEquals("", result2.out());
        assertEquals("", result2.error());

        assertFalse(Files.exists(Path.of("asdf")));
    }

    @Test
    @DisplayName("Failing Command Test")
    void failingCommandTest() {
        final VirtualTerminal terminal = new VirtualTerminal();
        terminal.setPrintStdout(true).setPrintStderr(true).setPrintCommand(true).setPrintExitCode(true);

        final CommandResult result = terminal.run("rm", "foo/bar/baz");

        assertFalse(result.isSuccessful());
        assertTrue(result.error().contains("No such file or directory"));
        assertEquals(1, result.exitCode());
    }
}
