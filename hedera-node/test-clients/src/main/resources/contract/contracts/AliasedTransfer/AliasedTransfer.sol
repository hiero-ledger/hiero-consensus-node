// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.12;

import './AliasedOperator.sol';

contract AliasedTransfer {
    address aliasedOperator;

    constructor() public {}

    function deployWithCREATE2(address token) external returns (address operator) {
        bytes memory bytecode = type(AliasedOperator).creationCode;
        bytes32 salt = keccak256(abi.encodePacked(token));
        assembly {
            operator := create2(0, add(bytecode, 32), mload(bytecode), salt)
        }
        AliasedOperator(operator).initialize(token);

        aliasedOperator = operator;
    }

    function transfer(address to, uint value) public {
        AliasedOperator(aliasedOperator).transfer(to, value);
    }

    function giveTokensToOperator(address token, address sender, int64 value) public {
        address(0x167).call(abi.encodeWithSignature("transferToken(address,address,address,int64)", token, sender, aliasedOperator, value));
    }
}
