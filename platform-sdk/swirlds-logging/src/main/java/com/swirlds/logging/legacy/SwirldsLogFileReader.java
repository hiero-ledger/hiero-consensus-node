// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * An object that is able to read a swirlds log file.
 */
public class SwirldsLogFileReader<T> extends SwirldsLogReader<T> {

    private final SwirldsLogParser<T> parser;
    private final BufferedReader fileReader;

    /**
     * Create a new log file reader.
     *
     * @param logFile
     * 		The log file to read.
     * @param parser
     * 		The parser that should be used to read the log file.
     * @throws FileNotFoundException
     * 		thrown when the file is not found
     */
    public SwirldsLogFileReader(final File logFile, final SwirldsLogParser<T> parser) throws FileNotFoundException {
        this.parser = parser;
        this.fileReader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected T readNextEntry() throws IOException {
        while (true) {
            String line = fileReader.readLine();
            if (line == null) {
                return null;
            }

            // skip empty lines
            if (line.strip().isEmpty()) {
                continue;
            }

            T entry = parser.parse(line);
            if (entry != null) {
                return entry;
            }
        }
    }
}
