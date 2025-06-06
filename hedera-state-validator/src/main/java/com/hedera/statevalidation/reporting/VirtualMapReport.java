// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.reporting;

public class VirtualMapReport {

    StorageReport pathToHashReport = new StorageReport();
    StorageReport pathToKeyValueReport = new StorageReport();
    StorageReport objectKeyToPathReport = new StorageReport();

    public StorageReport pathToHashReport() {
        return pathToHashReport;
    }

    public void setPathToHashReport(final StorageReport pathToHashReport) {
        this.pathToHashReport = pathToHashReport;
    }

    public StorageReport pathToKeyValueReport() {
        return pathToKeyValueReport;
    }

    public void setPathToKeyValueReport(final StorageReport pathToKeyValueReport) {
        this.pathToKeyValueReport = pathToKeyValueReport;
    }

    public StorageReport objectKeyToPathReport() {
        return objectKeyToPathReport;
    }

    public void setObjectKeyToPathReport(final StorageReport objectKeyToPathReport) {
        this.objectKeyToPathReport = objectKeyToPathReport;
    }
}
