// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import org.hiero.base.constructable.ConstructableRegistry;
import org.openjdk.jmh.annotations.Setup;

public class CryptoBenchMerkleDb extends CryptoBench {

    @Setup
    public static void setupMerkleDb() throws Exception {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.merkledb");
    }

    @Override
    public void beforeTest(String name) {
        super.beforeTest(name);
        updateMerkleDbPath();
    }

    public static void main(String[] args) throws Exception {
        CryptoBenchMerkleDb.setupMerkleDb();
        final CryptoBenchMerkleDb bench = new CryptoBenchMerkleDb();
        bench.setup();
        bench.beforeTest();
        bench.transferPrefetch();
        bench.afterTest();
        bench.destroy();
    }
}
