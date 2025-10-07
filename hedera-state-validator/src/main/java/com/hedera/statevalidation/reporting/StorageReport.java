// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.reporting;

public class StorageReport {
    private long minKey;
    private long maxKey;

    private long onDiskSizeInMb;
    private long numberOfStorageFiles;

    private double wastePercentage;
    private long duplicateItems;
    private long itemCount;

    public long minKey() {
        return minKey;
    }

    public void setMinKey(final long minKey) {
        this.minKey = minKey;
    }

    public long maxKey() {
        return maxKey;
    }

    public void setMaxKey(final long maxKey) {
        this.maxKey = maxKey;
    }

    public long onDiskSizeInMb() {
        return onDiskSizeInMb;
    }

    public void setOnDiskSizeInMb(final long onDiskSizeInMb) {
        this.onDiskSizeInMb = onDiskSizeInMb;
    }

    public long numberOfStorageFiles() {
        return numberOfStorageFiles;
    }

    public void setNumberOfStorageFiles(final long numberOfStorageFiles) {
        this.numberOfStorageFiles = numberOfStorageFiles;
    }

    public double wastePercentage() {
        return wastePercentage;
    }

    public void setWastePercentage(final double wastePercentage) {
        this.wastePercentage = wastePercentage;
    }

    public long duplicateItems() {
        return duplicateItems;
    }

    public void setDuplicateItems(final long duplicateItems) {
        this.duplicateItems = duplicateItems;
    }

    public long itemCount() {
        return itemCount;
    }

    public void setItemCount(final long itemCount) {
        this.itemCount = itemCount;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(String.format("  Key Range: %d to %d\n", minKey, maxKey));
        sb.append(String.format("  Size: %d MB\n", onDiskSizeInMb));
        sb.append(String.format("  Files: %d\n", numberOfStorageFiles));
        sb.append(String.format("  Items: %,d\n", itemCount));

        sb.append(String.format("  Waste: %.2f%%\n", wastePercentage));

        if (duplicateItems > 0) {
            sb.append(String.format("  Duplicates: %,d\n", duplicateItems));
        }

        return sb.toString();
    }
}
