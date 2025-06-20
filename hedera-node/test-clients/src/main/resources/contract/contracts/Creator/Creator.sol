// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.7;

import './Permit.sol';

contract Creator {
    Permit creation;

    constructor() {
        address permit;
        bytes memory bytecode = type(Permit).creationCode;
        bytes32 salt = keccak256(abi.encodePacked("permit"));
        assembly {
            permit := create2(0, add(bytecode, 32), mload(bytecode), salt)
        }
        creation = Permit(permit);
    }

    function isWhitelisted(address whitelister) public returns (bool) {
        return creation.isWhitelisted(whitelister);
    }
}
