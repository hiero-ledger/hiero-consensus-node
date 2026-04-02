// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.10;

contract AccessListTargetContract {

    uint public x = 3; // storage slot 0
    uint public y = 4; // storage slot 1
    uint public z = 7; // storage slot 2m this should equal to (x + y) for not to charge SSTORE on first execute() call

    function execute() public returns (uint256) {
        z = x + y;
        return z;
    }
}
