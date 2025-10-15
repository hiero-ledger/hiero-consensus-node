// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.7;

contract Evm70BlsValidation {

    function callBls12(address precompileAddress, bytes memory callData) public returns (bytes memory response){
        (bool success, bytes memory res) = address(precompileAddress).call(callData);

        require(success == true);
        return res;
    }
}
