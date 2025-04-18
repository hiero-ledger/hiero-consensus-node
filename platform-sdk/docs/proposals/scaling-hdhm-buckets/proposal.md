# Scalability - adjusting the number of HalfDiskHashMap buckets

---

## Summary

`HalfDiskHashMap` is a data structure that holds virtual key to virtual path mapping. Virtual paths
are required to locate key/value records in virtual maps. For every key (object) a path (long) is
stored in `HalfDiskHashMap`. All requests like `VirtualMap.get(key)` first get a path for the key,
and then retrieve the value using this path.

`HalfDiskHashMap` stores key/path mappings in buckets. Every bucket contain the following info:
  * bucket ID: 0 to N-1, where N is the number of buckets
  * set of bucket entries, where an entry is
    * key hash code
    * key bytes
    * value (path)

When a key is added to `HalfDiskHashMap`, the bucket to add it to is found using key's hash code.
The number of buckets is always a power of two, so the operation is very cheap and straightforward,
just `hashCode & (N - 1)`. It's to take a few lowest bits of the hash code.

The number of buckets is currently fixed and cannot be changed. It's set based on projected map
size in assumption that a bucket should not contain more than 32 key/path entries. For example,
if map size is 1_000_000_000, then the number of buckets will be about 32M (2^25, to be precise).
In reality, maps usually contain fewer entities than projected, so some buckets are empty, and
many buckets contain less than 32 entries. However, when map grows in size, buckets contain more
and more entries, and eventually key lookup in buckets become too slow / expensive.

To overcome this penalty, there should be a mechanism to increase the number of buckets, when
needed. One way to understand that scaling is needed is to check average number of entries in a
bucket. If it exceeds a threshold, increase the number of buckets. Another approach is to adjust
projected map size and recalculate the number of buckets from it. This proposal is focused on
the latter option.

## Architecture

When a `MerkleDbDataSource` instance is created, projected map size is passed to it as a part of
`MerkleDbTableConfig`. If this is a new (empty) data source, the map size hint is used by HDHM
to calculate the number of buckets as described above. If a data source is loaded from a snapshot,
the hint is ignored, and the number of buckets is read from HDHM metadata file.

Instead, the hint may be used to calculate the updated number of buckets. If it's the same as
what's in HDHM metadata, no further actions are needed. If it is different, bucket index expanding
is required.

### Expanding bucket index

Number of buckets is always a power of 2. If the new number of buckets is different from the
existing one, it must be 2, or 4, or 8, etc. times larger than what's currently on disk. Let's
assume it's 2 times larger, to keep calculations below easy.

Let's assume the old number of buckets is 2^X, and the new (updated) number is 2^(X+1), i.e.
two times larger. For example, this would happen if a map was originally created with map size
hint 1_000_000, and then the hint is changed to 2_000_000.

For every bucket, let's store two entries in the new bucket index:
* 0<bucketID>
* 1<bucketID>
where `bucketID` is X bucket ID bits. This means, two entries in bucket index will point to the
same data location on disk. Bucket data on disk contains keys, X lower bits of keys hash codes
equal to bucket ID. X+1 lower bits may or may not be equal to bucket ID.

### Key lookups after index expanding

How key/path lookups in HDHM work today:
  * Get key hash code
  * Take X lower bits of the hash code, this will be bucket ID
  * Look up data location for the bucket ID
  * Load the bucket from disk
  * Iterate over all bucket entries
  * For every entry, check the hash code and key bytes. If they are equal to the key in question,
    return entry value (path) to the caller, otherwise proceed to the next entry

No changes are needed to these steps after the bucket index is expanded.

### Bucket cleanup after index expanding

There is an issue after expanding, though. For every bucket before expanding, there will be two
bucket index entries after expanding pointing to the same bucket data, as if there were two
different buckets. Some keys will be incorrect for one of these two new buckets, some will be
incorrect for the other one depending on the X+1 bit. These stale keys should be removed.

To clean up all buckets at node startup would be too expensive, as there may be too many buckets
to process. Instead, cleanup will be done lazily, only when a bucket is updated. When a bucket is
loaded from disk, all its entries are scanned, entry key hash codes (lower X+1 bits) are compared
to the bucket ID, if they don't match, the entry is removed.

**Open question**: should a bucket be marked as sanitized for X+1 bits, so this cleanup process is
not run again and again on every load from disk?

### Example

Virtual map size: `200`. Calculated HDHM buckets: `8` (200 / 32, rounded up), bucket index mask is
`0b0111`. Assume there is a bucket with ID `3`, it contains keys with hash codes with lower 3 bits
set to `011`. Bucket index contains 8 elements, index[3] == DL3 (bucket 3 location on disk), all
other index entries are zeroes. Let's also assume bucket 3 contains two keys, with hash codes
`0b01001011` and `0b01000011` (lower 3 bits of both are `011`, this matches the bucket ID).

Now the node is restarted, and map size is increased to 500. The calculated number of buckets is 16
(500 / 32, rounded up), the mask is `0b1111`. Bucket index is now 16 elements, index[3] and index[11]
are set to DL3, all other index entries are zeroes.

When a key with hash code `0b01001011` is requested from the HDHM, it will be looked up in bucket
11 (which is `0b01001011 & 0b1111`). Bucket index for this bucket points to DL3. When a bucket is
loaded from this location, HDHM will iterate over all its entries, find an entry that corresponds
to the key (both key hash code and key bytes match the key), and return its value (path). Similarly,
a key with hash code `0b01000011` will be found using bucket 3.

Now let's add a new key to the map, key hash code is `0b10001011`. The corresponding bucket ID is
11 (hash code & bucket mask). The bucket is loaded from disk, a new entry is added to it. At the
same time, the entry for key `0b01000011` is removed from the bucket, since its hash code combined
with the bucket mask corresponds to bucket 3, not bucket 11. Then bucket 11 with the two entries
is written to disk, and index[11] is updated to the new disk location.

### Performance

Expanding index. Currently, HDHM bucket index is stored in memory. To double its size is to copy
a number of memory chunks, which should be very fast. For example, 1B maps will have bucket index
of 32M elements, or 32 1M off-heap chunks. 32 calls to copy 1M memory chunks should be close to
instant. In the future, when indices are stored on disk, we will need to make sure index chunks
are copied efficiently.

Bucket updates to remove stale entries with wrong hash codes. It can be done when buckets are
loaded for updates from `HDHM.endWriting()`. Buckets are loaded from disk using `loadFrom()`
method, which scans through the whole bucket buffer and counts bucket entries. This method can
be enhanced to remove wrong entries, it should not slow down the method significantly.

### Testing

### Other concerns

State validation tool will need to be updated, so it doesn't fail when a bucket entry with a
wrong hash code (doesn't correspond to bucket ID, with the current bucket mask) is found. For
example, every bucket may have a field with the latest bucket mask this bucket was written to.
All hash codes combined with this mask must match the bucket ID. The mask may or may not be
the current HDHM bucket mask.


