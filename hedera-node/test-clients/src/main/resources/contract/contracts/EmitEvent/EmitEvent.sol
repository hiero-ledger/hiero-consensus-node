// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.7;

contract EmitEvent {

    event AnEvent(uint val);
    uint storedData;

    function set(uint x) public {

        storedData = x;

    }

    function get() public returns (uint) {

        emit AnEvent(7);
        return storedData;

    }

}
