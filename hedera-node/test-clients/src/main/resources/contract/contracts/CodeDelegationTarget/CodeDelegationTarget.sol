// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

contract CodeDelegationTarget {

    event FallbackCalled(
        address sender,
        uint256 value,
        bytes data
    );

    event MethodCalled(
        address sender,
        uint256 value,
        uint256 param
    );

    uint256 private storedValue;

    fallback() external payable {
        emit FallbackCalled(msg.sender, msg.value, msg.data);
    }

    function storeAndEmit(uint256 param) external payable {
        storedValue = param;
        emit MethodCalled(msg.sender, msg.value, param);
    }

    function getValue() public view returns (uint256) {
        return storedValue;
    }

    function executeCall(address to, uint256 value, bytes memory data) public {
        assembly {
            let result := call(gas(), to, value, add(data, 0x20), mload(data), 0, 0)

            switch result case 0 {
                let size := returndatasize()
                let ptr := mload(0x40)
                returndatacopy(ptr, 0, size)
                revert(ptr, size)
            }
            default {}
        }
    }
}
