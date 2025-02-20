// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import static com.swirlds.cli.logging.LogProcessingUtils.colorizeLogLineAnsi;

import com.swirlds.common.formatting.TextEffect;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.ZoneId;

/**
 * An entry point that log output can be piped into to colorize it with ANSI.
 */
public class StdInOutColorize {
    public static void main(final String[] args) throws IOException {
        TextEffect.setTextEffectsEnabled(true);

        final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));

        String rawLine = bufferedReader.readLine();
        while (rawLine != null) {
            final String outputLine = colorizeLogLineAnsi(rawLine, ZoneId.systemDefault());
            System.out.println(outputLine);

            rawLine = bufferedReader.readLine();
        }

        bufferedReader.close();
    }
}
