// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract CallInConstructor {
    address constant CONSOLE_ADDRESS = address(0x000000000000000000636F6e736F6c652e6c6f67);

    constructor() {
        address somebodyToCall = CONSOLE_ADDRESS;
        assembly {
            let r := staticcall(gas(), somebodyToCall, 0, 0, 0, 0)
        }
    }

    function callSomebody() view public {
        address somebodyToCall = CONSOLE_ADDRESS;
        assembly {
            let r := staticcall(gas(), somebodyToCall, 0, 0, 0, 0)
        }
    }
}
