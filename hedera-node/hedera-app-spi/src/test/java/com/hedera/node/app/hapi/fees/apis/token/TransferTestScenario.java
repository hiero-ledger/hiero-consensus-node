package com.hedera.node.app.hapi.fees.apis.token;


public class TransferTestScenario {
    String api;
    int numSignatures;
    int numAccountsInvolved;
    int numFTNoCustomFeeEntries;
    int numNFTNoCustomFeeEntries;
    int numFTWithCustomFeeEntries;
    int numNFTWithCustomFeeEntries;
    int numAutoAssociationsCreated;
    int numAutoAccountsCreated;
    double expectedFee;

    public TransferTestScenario(String api, int numSignatures, int numAccountsInvolved,
                        int numFTNoCustomFeeEntries, int numNFTNoCustomFeeEntries,
                        int numFTWithCustomFeeEntries, int numNFTWithCustomFeeEntries,
                        int numAutoAssociationsCreated, int numAutoAccountsCreated,
                        double expectedFee) {
        this.api = api;
        this.numSignatures = numSignatures;
        this.numAccountsInvolved = numAccountsInvolved;
        this.numFTNoCustomFeeEntries = numFTNoCustomFeeEntries;
        this.numNFTNoCustomFeeEntries = numNFTNoCustomFeeEntries;
        this.numFTWithCustomFeeEntries = numFTWithCustomFeeEntries;
        this.numNFTWithCustomFeeEntries = numNFTWithCustomFeeEntries;
        this.numAutoAssociationsCreated = numAutoAssociationsCreated;
        this.numAutoAccountsCreated = numAutoAccountsCreated;
        this.expectedFee = expectedFee;
    }

    @Override
    public String toString() {
        return "TransferTestScenario{" +
                "api='" + api + '\'' +
                ", numSignatures=" + numSignatures +
                ", numAccountsInvolved=" + numAccountsInvolved +
                ", numFTNoCustomFeeEntries=" + numFTNoCustomFeeEntries +
                ", numNFTNoCustomFeeEntries=" + numNFTNoCustomFeeEntries +
                ", numFTWithCustomFeeEntries=" + numFTWithCustomFeeEntries +
                ", numNFTWithCustomFeeEntries=" + numNFTWithCustomFeeEntries +
                ", numAutoAssociationsCreated=" + numAutoAssociationsCreated +
                ", numAutoAccountsCreated=" + numAutoAccountsCreated +
                ", expectedFee=" + expectedFee +
                '}';
    }
}