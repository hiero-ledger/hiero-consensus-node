# XTS Hammer Tests - Test Files

**Gradle Task:** `hammer`
**Test Source:** `src/hammer/java`

## Test Files (3 files)

### platform-sdk/swirlds-merkledb (3 files)

1. `platform-sdk/swirlds-merkledb/src/hammer/java/com/swirlds/merkledb/files/MemoryIndexDiskKeyValueStoreCompactionHammerTest.java`
2. `platform-sdk/swirlds-merkledb/src/hammer/java/com/swirlds/merkledb/files/DataFileReaderHammerTest.java`
3. `platform-sdk/swirlds-merkledb/src/hammer/java/com/swirlds/merkledb/files/DataFileCollectionCompactionHammerTest.java`

## Notes

- Hammer tests are stress tests for database operations
- They run on dedicated runners (`hiero-cn-hammer-linux`)
- Test the MerkleDB storage layer under heavy load
- Focus on file compaction and data file reading under concurrent access
