/*
 * Copyright (C) 2018-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.cli.utility;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * An entry point that log output can be piped into squelching spam lines.
 */
public class SpamSquelcher {

    public static final String startOfSpam = "] Bad JNI lookup accessibilityHitTest";
    public static final String endOfSpam = "Exception in thread \"AppKit Thread\" java.lang.NoSuchMethodError: accessibilityHitTest";
    /**
     * Tracks if log line is inside spam boundaries
     */
    private static boolean inSpam;
    /**
     * Tracks if next log line is inside spam boundaries
     */
    private static boolean nextLineIsSpam;

    /**
     * Hidden constructor
     */
    private SpamSquelcher(){}

    /**
     * Checks if log line is spam
     *
     * @param line the log line string
     * @return true if log line is spam, false otherwise
     */
    public static boolean lineIsSpam(String line) {

        if (!nextLineIsSpam) {
            inSpam = false;
        }

        if (!inSpam) {
            if (line.contains(startOfSpam)) {
                inSpam = true;
                nextLineIsSpam = true;
            }
        } else {
            if (line.contains(endOfSpam)) {
                nextLineIsSpam = false;
            }
        }

        return inSpam;
    }

    /**
     * Resets both inSpam and nextLineIsSpam states to default values
     * <p>
     * Useful for SpamSquelcher tests
     */
    public static void resetSpamStatus(){
        inSpam = false;
        nextLineIsSpam = false;
    }


    /**
     * Main entrypoint for SpamSquelcher utility
     *
     * @param args program args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line = reader.readLine();
            while (line != null) {
                if (!lineIsSpam(line)) {
                    System.out.println(line);
                    System.out.flush();
                }
                line = reader.readLine();
            }
        }
    }
}