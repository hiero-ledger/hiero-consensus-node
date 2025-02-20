// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swirlds.logging.legacy.SwirldsLogParser;

/**
 * A parser that reads logs in json format.
 */
public class JsonParser implements SwirldsLogParser<JsonLogEntry> {

    private static final JsonFactory factory = new JsonFactory();

    @Override
    public JsonLogEntry parse(String line) {
        ObjectMapper mapper = new ObjectMapper(factory);
        JsonNode rootNode;
        try {
            rootNode = mapper.readTree(line);
        } catch (JsonProcessingException e) {
            return null;
        }

        if (rootNode == null) {
            return null;
        }

        return new JsonLogEntry(rootNode);
    }
}
