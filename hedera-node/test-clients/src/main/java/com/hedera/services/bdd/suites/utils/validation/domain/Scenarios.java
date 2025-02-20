// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.validation.domain;

public class Scenarios {
    FileScenario file;
    CryptoScenario crypto;
    SysFilesUpScenario sysFilesUp;
    SysFilesDownScenario sysFilesDown;
    ContractScenario contract;
    ConsensusScenario consensus;
    StakingScenario staking;
    VersionInfoScenario versions;

    public SysFilesUpScenario getSysFilesUp() {
        return sysFilesUp;
    }

    public void setSysFilesUp(SysFilesUpScenario sysFilesUp) {
        this.sysFilesUp = sysFilesUp;
    }

    public SysFilesDownScenario getSysFilesDown() {
        return sysFilesDown;
    }

    public void setSysFilesDown(SysFilesDownScenario sysFilesDown) {
        this.sysFilesDown = sysFilesDown;
    }

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

    public VersionInfoScenario getVersions() {
        return versions;
    }

    public void setVersions(VersionInfoScenario versions) {
        this.versions = versions;
    }
}
