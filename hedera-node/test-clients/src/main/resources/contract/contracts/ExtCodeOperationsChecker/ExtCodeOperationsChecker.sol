// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.0;

contract ExtCodeOperationsChecker {
    function sizeOf(address _address) public view returns (uint256 size) {
        assembly {
            size := extcodesize(_address)
        }
    }

    function hashOf(address _address) public view returns (bytes32 hash) {
        assembly {
            hash := extcodehash(_address)
        }
    }

    function codeCopyOf(address _address) public view returns (bytes memory code) {
        assembly {
            // retrieve the size of the code
            let size := extcodesize(_address)
            // allocate output byte array
            code := mload(0x40)
            // new "memory end" including padding
            mstore(0x40, add(code, and(add(add(size, 0x20), 0x1f), not(0x1f))))
            // store length in memory
            mstore(code, size)
            // get code
            extcodecopy(_address, add(code, 0x20), 0, size)
            // return the calldata
            return(add(code, 0x20), size)
        }
    }
}
