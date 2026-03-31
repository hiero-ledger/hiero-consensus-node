// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.10;

contract AccessListTargetContract {

    uint public x = 3; // storage slot 0
    uint public y = 4; // storage slot 1

    function execute() public view returns (uint256) {
        return x + y;
    }
}
