// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.file;

public class UploadProgress {
    private boolean[] finishedAppends;

    public void initializeFor(final int appendsRequired) {
        finishedAppends = new boolean[appendsRequired];
    }

    public void markFinished(final int i) {
        assertInitialized();
        if (i < 0 || i >= finishedAppends.length) {
            throw new IllegalArgumentException("Only " + finishedAppends.length + " appends to do, cannot mark " + i);
        }
        finishedAppends[i] = true;
    }

    public boolean[] finishedAppends() {
        assertInitialized();
        return finishedAppends;
    }

    public int finishedAppendPrefixLength() {
        int i;
        for (i = finishedAppends.length - 1; i >= 0 && !finishedAppends[i]; i--) {
            // No-op
        }
        if (i == -1) {
            return 0;
        } else {
            for (int j = 0; j <= i; j++) {
                if (!finishedAppends[j]) {
                    return -1;
                }
            }
            return i + 1;
        }
    }

    public boolean isInitialized() {
        return finishedAppends != null;
    }

    private void assertInitialized() {
        if (!isInitialized()) {
            throw new IllegalStateException("Upload progress not initialized");
        }
    }
}
