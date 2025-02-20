// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

// The "HSCS_EVM_005_TransfersWithSubLevelCallsBetweenContracts spec calls "subLevelNonPayableCall" function, which is
// missing in this solidity file. The function was manually added in the SubLevelTransferring ABI file and is identical
// to the function with the same name in TopLevelTransferring ABI

contract SubLevelTransferring {

    constructor() payable {}

    function receiveTinybars() public payable returns (bool) {
        return true;
    }

    function nonPayableReceive() public pure returns (bool) {
        return true;
    }
}
