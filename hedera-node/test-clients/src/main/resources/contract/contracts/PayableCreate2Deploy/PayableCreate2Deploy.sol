// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity >=0.8.0;

contract PayableCreate2Deploy {

    function testPayableCreate() external payable {
        bytes memory bytecode = type(PayableCreate).creationCode;
        assembly {
            if iszero(create2(callvalue(), add(bytecode, 0x20), mload(bytecode), caller())) {
                revert(0,0)
            }
        }
    }
}

contract PayableCreate {

    constructor() payable {

    }
}
