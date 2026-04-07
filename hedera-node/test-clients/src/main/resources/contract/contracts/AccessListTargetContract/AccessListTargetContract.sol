// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.10;

contract AccessListTargetContract {

    uint public local1 = 3; // storage slot 0
    uint public local2 = 4; // storage slot 1
    uint public res1 = 7; // storage slot 2, this should equal to (x + y) for not to charge SSTORE on first execute() call
    uint public res2 = 0; // storage slot 3

    function execute() public returns (uint256) {
        res1 = local1 + local2;
        return res1;
    }

    // when executing on delegation we are not using local1 and local2, because storage slots are EOAs storage slots and values are 0
    function executeDelegation(uint256 param1, uint256 param2) public returns (uint256) {
        res2 = param1 + param2;
        return res2;
    }
}
