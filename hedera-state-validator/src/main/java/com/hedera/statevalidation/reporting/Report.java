// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.reporting;

import static com.hedera.statevalidation.validators.Constants.NODE_NAME;

public class Report {

    private String nodeName = NODE_NAME;

    private StorageReport pathToHashReport;

    private StorageReport pathToKeyValueReport;

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(final String nodeName) {
        this.nodeName = nodeName;
    }

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
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Report for node: %s\n\n", nodeName));

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
