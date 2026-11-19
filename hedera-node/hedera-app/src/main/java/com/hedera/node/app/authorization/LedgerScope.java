// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.authorization;

/**
 * Shard and realm half of a {@code shard.realm.num} id. Callers still decide what the entity number means.
 */
final class LedgerScope {
    private LedgerScope() {}

    static boolean sameShardAndRealm(
            final long entityShard, final long entityRealm, final long shard, final long realm) {
        return entityShard == shard && entityRealm == realm;
    }
}
