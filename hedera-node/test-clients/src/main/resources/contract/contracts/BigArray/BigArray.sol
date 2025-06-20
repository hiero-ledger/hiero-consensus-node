// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.7;

contract BigArray {
    uint256[] public bigArray;

    // Functions for array passing and storage
    function setSize(uint256 _size) public {
        bigArray = new uint256[](_size);
        // Initialize all locations because zeros are not actually persisted.
        for (uint i = 0; i < _size; i++) {
            bigArray[i] = 17;
        }
    }

    function changeArray(uint256 _value) public {
        bigArray[1] = _value;
    }
}
