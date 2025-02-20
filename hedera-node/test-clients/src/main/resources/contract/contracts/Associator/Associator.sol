// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.12;

import './Permit.sol';
import './HederaTokenService.sol';

contract Associator {
    address creation;

    constructor() {
        address permit;
        bytes memory bytecode = type(Permit).creationCode;
        bytes32 salt = keccak256(abi.encodePacked("permit"));
        assembly {
            permit := create2(0, add(bytecode, 32), mload(bytecode), salt)
        }
        creation = permit;
    }

    function associate(address account, address token) external {
        Permit(creation).associate(account, token);
    }
}
