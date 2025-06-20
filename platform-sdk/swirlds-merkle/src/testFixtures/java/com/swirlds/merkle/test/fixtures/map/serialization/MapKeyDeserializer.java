// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.serialization;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;

/**
 * Deserializer for MapKey
 */
public class MapKeyDeserializer extends KeyDeserializer {
    /**
     * {@inheritDoc}
     * Deserializes the MapKey
     * @param key String of a specific format that needs to be de-serialized to MapKey
     * @param deserializationContext
     * @return De-serialized MapKey with shardId, realmID, accountID
     */
    @Override
    public MapKey deserializeKey(String key, DeserializationContext deserializationContext) {

        String str = key.substring(key.indexOf("[") + 1, key.lastIndexOf("]"));
        String[] fields = str.split(",");
        return new MapKey(
                Long.parseLong(fields[0].trim()), Long.parseLong(fields[1].trim()), Long.parseLong(fields[2].trim()));
    }
}
