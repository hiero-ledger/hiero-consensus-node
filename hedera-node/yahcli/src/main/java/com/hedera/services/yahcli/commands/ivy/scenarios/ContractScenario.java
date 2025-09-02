// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.scenarios;

public class ContractScenario {
    public static String NOVEL_CONTRACT_NAME = "Multipurpose";
    public static String PERSISTENT_CONTRACT_NAME = "Multipurpose";
    public static String DEFAULT_CONTRACT_RESOURCE = "contract/contracts/Multipurpose/Multipurpose.sol";
    public static String DEFAULT_BYTECODE_RESOURCE = "contract/contracts/Multipurpose/Multipurpose.bin";
    public static int DEFAULT_LUCKY_NUMBER = 42;

    PersistentContract persistent;

    public PersistentContract getPersistent() {
        return persistent;
    }

    public void setPersistent(PersistentContract persistent) {
        this.persistent = persistent;
    }
}
