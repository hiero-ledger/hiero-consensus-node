// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import java.nio.file.Path;

/**
 * Simple class for building and holding the set of sub-paths for data in a MerkleDb datasource directory
 */
public class MerkleDbPaths {

    public final Path storageDir;
    public final Path metadataFile;
    public final Path pathToDiskLocationInternalNodesFile;
    public final Path pathToDiskLocationLeafNodesFile;
    public final Path hashStoreRamFile;
    public final Path hashStoreDiskDirectory;
    public final Path keyToPathDirectory;
    public final Path pathToKeyValueDirectory;

    /**
     * Create a set of all the sub-paths for stored data in a MerkleDb data source.
     *
     * @param storageDir
     * 		directory to store data files in
     */
    public MerkleDbPaths(final Path storageDir) {
        this.storageDir = storageDir;
        metadataFile = storageDir.resolve("table_metadata.pbj");
        pathToDiskLocationInternalNodesFile = storageDir.resolve("pathToDiskLocationInternalNodes.ll");
        pathToDiskLocationLeafNodesFile = storageDir.resolve("pathToDiskLocationLeafNodes.ll");
        hashStoreRamFile = storageDir.resolve("internalHashStoreRam.hl");
        hashStoreDiskDirectory = storageDir.resolve("internalHashStoreDisk");
        keyToPathDirectory = storageDir.resolve("objectKeyToPath");
        pathToKeyValueDirectory = storageDir.resolve("pathToHashKeyValue");
    }
}
