// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

// Writes its own storage, optionally around calls to another contract
contract StorageAroundCall {
    mapping(uint256 => uint256) public values;

    function set(uint256 key, uint256 value) external {
        values[key] = value;
    }

    function setTwo(uint256 key1, uint256 value1, uint256 key2, uint256 value2) external {
        values[key1] = value1;
        values[key2] = value2;
    }

    function setAndRevert(uint256 key, uint256 value) external {
        values[key] = value;
        revert();
    }

    function setCallSet(
        uint256 keyBefore,
        uint256 valueBefore,
        address target,
        bytes[] calldata calls,
        uint256 keyAfter,
        uint256 valueAfter,
        bool revertAfter
    ) external {
        values[keyBefore] = valueBefore;
        for (uint256 i = 0; i < calls.length; i++) {
            (bool success, ) = target.call(calls[i]);
            require(success);
        }
        values[keyAfter] = valueAfter;
        if (revertAfter) {
            revert();
        }
    }
}
