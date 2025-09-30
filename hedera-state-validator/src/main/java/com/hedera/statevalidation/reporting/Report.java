// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.reporting;

import static com.hedera.statevalidation.validators.Constants.NODE_NAME;

public class Report {
    private String nodeName = NODE_NAME;

    @InvariantProperty
    private long roundNumber;

    private long numberOfAccounts;
    private StateReport stateReport = new StateReport();
    private VirtualMapReport vmReport = new VirtualMapReport();

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(final String nodeName) {
        this.nodeName = nodeName;
    }

    public long getRoundNumber() {
        return roundNumber;
    }

    public void setRoundNumber(final long roundNumber) {
        this.roundNumber = roundNumber;
    }

    public long getNumberOfAccounts() {
        return numberOfAccounts;
    }

    public void setNumberOfAccounts(final long numberOfAccounts) {
        this.numberOfAccounts = numberOfAccounts;
    }

    public StateReport getStateReport() {
        return stateReport;
    }

    public void setStateReport(final StateReport stateReport) {
        this.stateReport = stateReport;
    }

    public VirtualMapReport getVmReport() {
        return vmReport;
    }

    public void setVmReport(final VirtualMapReport vmReport) {
        this.vmReport = vmReport;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("REPORT SUMMARY\n");
        sb.append("=============\n");
        sb.append(String.format("Node: %s\n", nodeName));
        sb.append(String.format("Round Number: %d\n", roundNumber));
        sb.append(String.format("Number of Accounts: %d\n", numberOfAccounts));

        sb.append("\nSTATE REPORT\n");
        sb.append(stateReport.toString());

        sb.append("\nVIRTUAL MAP REPORT\n");
        sb.append(vmReport.toString());

        return sb.toString();
    }
}
