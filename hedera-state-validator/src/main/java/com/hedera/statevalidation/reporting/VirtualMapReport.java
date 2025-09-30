// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.reporting;

public class VirtualMapReport {

    StorageReport pathToHashReport;
    StorageReport pathToKeyValueReport;

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

    @Override
    public String toString() {
        @SuppressWarnings("StringBufferReplaceableByString")
        StringBuilder sb = new StringBuilder();
        sb.append("============\n");

        if (pathToHashReport != null) {
            sb.append("Path-to-Hash Storage:\n");
            sb.append(pathToHashReport);
            sb.append("\n");
        }

        if (pathToKeyValueReport != null) {
            sb.append("Path-to-KeyValue Storage:\n");
            sb.append(pathToKeyValueReport);
        }

        return sb.toString();
    }
}
