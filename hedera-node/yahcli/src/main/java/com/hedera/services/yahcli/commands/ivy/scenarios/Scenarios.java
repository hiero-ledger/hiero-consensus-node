// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.scenarios;

public class Scenarios {
    FileScenario file;
    CryptoScenario crypto;
    ContractScenario contract;
    ConsensusScenario consensus;
    StakingScenario staking;

    public CryptoScenario getCrypto() {
        return crypto;
    }

    public void setCrypto(CryptoScenario crypto) {
        this.crypto = crypto;
    }

    public FileScenario getFile() {
        return file;
    }

    public void setFile(FileScenario file) {
        this.file = file;
    }

    public ContractScenario getContract() {
        return contract;
    }

    public void setContract(ContractScenario contract) {
        this.contract = contract;
    }

    public ConsensusScenario getConsensus() {
        return consensus;
    }

    public StakingScenario getStaking() {
        return staking;
    }

    public void setStaking(StakingScenario staking) {
        this.staking = staking;
    }

    public void setConsensus(ConsensusScenario consensus) {
        this.consensus = consensus;
    }
}
