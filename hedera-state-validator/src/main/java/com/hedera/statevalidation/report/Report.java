// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.report;

import static com.hedera.statevalidation.util.ConfigUtils.NODE_NAME;

public class Report {

    private String nodeName = NODE_NAME;

    private StorageReport pathToKeyValueReport;
    private StorageReport keyToPathReport;
    private StorageReport pathToHashReport;

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(final String nodeName) {
        this.nodeName = nodeName;
    }

    public StorageReport pathToKeyValueReport() {
        return pathToKeyValueReport;
    }

    public void setPathToKeyValueReport(final StorageReport pathToKeyValueReport) {
        this.pathToKeyValueReport = pathToKeyValueReport;
    }

    public StorageReport keyToPathReport() {
        return keyToPathReport;
    }

    public void setKeyToPathReport(final StorageReport keyToPathReport) {
        this.keyToPathReport = keyToPathReport;
    }

    public StorageReport pathToHashReport() {
        return pathToHashReport;
    }

    public void setPathToHashReport(final StorageReport pathToHashReport) {
        this.pathToHashReport = pathToHashReport;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Report for node: %s\n\n", nodeName));

        if (pathToHashReport != null) {
            sb.append("Path-to-Hash Storage:\n");
            sb.append(pathToHashReport);
            sb.append("\n");
        }

        if (keyToPathReport != null) {
            sb.append("Key-to-Path Storage:\n");
            sb.append(keyToPathReport);
            sb.append("\n");
        }

        if (pathToKeyValueReport != null) {
            sb.append("Path-to-KeyValue Storage:\n");
            sb.append(pathToKeyValueReport);
        }

        return sb.toString();
    }
}
