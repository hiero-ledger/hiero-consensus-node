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

    @Override
    public String toString() {
        @SuppressWarnings("StringBufferReplaceableByString")
        StringBuilder sb = new StringBuilder();

        sb.append("Path-to-Hash Storage:\n");
        sb.append(pathToHashReport.toString());

        sb.append("\nPath-to-KeyValue Storage:\n");
        sb.append(pathToKeyValueReport.toString());

        sb.append("\nObjectKey-to-Path Storage:\n");
        sb.append(objectKeyToPathReport.toString());

        return sb.toString();
    }
}
