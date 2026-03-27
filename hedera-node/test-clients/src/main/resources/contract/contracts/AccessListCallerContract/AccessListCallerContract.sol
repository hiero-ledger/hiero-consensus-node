// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.10;

contract AccessListCallerContract {

    function call(address target) public returns (uint256 response) {
        (bool success, bytes memory result) = target.call("execute()");
        response = success ? abi.decode(result, (uint256)) : 0;
    }
}
