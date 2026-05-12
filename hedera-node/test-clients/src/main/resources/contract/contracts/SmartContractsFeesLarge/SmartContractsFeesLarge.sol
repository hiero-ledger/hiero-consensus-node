// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

/**
 * Fixture contract used only for ContractGetBytecode fee-scaling tests.
 *
 * The checked-in .bin deploys runtime bytecode larger than 20_000 bytes so
 * the query fee includes PROCESSING_BYTES above the included threshold.
 */
contract SmartContractsFeesLarge {
    function marker() external pure returns (uint256) {
        return 1;
    }
}
