// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.Key;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.CommonUtils;

public class OverlappingKeyGenerator implements KeyGenerator {
    private static final Logger log = LogManager.getLogger(OverlappingKeyGenerator.class);

    private int nextKey = 0;
    private List<Key> precomputed = new ArrayList<>();
    private Map<String, PrivateKey> pkMap = new HashMap<>();

    public static OverlappingKeyGenerator withDefaultOverlaps() {
        return new OverlappingKeyGenerator(5, 1);
    }

    public static OverlappingKeyGenerator withAtLeastOneOverlappingByte(int keys) {
        return new OverlappingKeyGenerator(keys, 1);
    }

    private OverlappingKeyGenerator(int n, int minOverlapLen) {
        Set<ByteString> usedPrefixes = new HashSet<>();
        Map<ByteString, Key> byPrefix = new HashMap<>();
        while (precomputed.size() < n) {
            Key candidate = DefaultKeyGen.genAndRememberEd25519Key(pkMap);
            ByteString prefix = pubKeyPrefixOf(candidate, minOverlapLen);
            if (byPrefix.containsKey(prefix)) {
                if (!usedPrefixes.contains(prefix)) {
                    precomputed.add(byPrefix.get(prefix));
                    usedPrefixes.add(prefix);
                }
                if (precomputed.size() < n) {
                    precomputed.add(candidate);
                }
            } else {
                byPrefix.put(prefix, candidate);
            }
        }
        log.debug("**** Hexed Public Keys ****");
        precomputed.stream()
                .forEach(k -> log.debug(CommonUtils.hex(k.getEd25519().toByteArray())));
    }

    private ByteString pubKeyPrefixOf(Key key, int prefixLen) {
        return key.getEd25519().substring(0, prefixLen);
    }

    @Override
    public Key genEd25519AndUpdateMap(Map<String, PrivateKey> mutablePkMap) {
        Key key = precomputed.get(nextKey);
        nextKey = (nextKey + 1) % precomputed.size();
        String hexPubKey = CommonUtils.hex(key.getEd25519().toByteArray());
        mutablePkMap.put(hexPubKey, pkMap.get(hexPubKey));
        return key;
    }
}
