// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.3;

contract BalanceLookup {
    constructor() public payable {}

    function() external payable {}

    function lookup(uint64 accountNum) external view returns(uint) {
        return address(uint120(accountNum)).balance;
    }
}
