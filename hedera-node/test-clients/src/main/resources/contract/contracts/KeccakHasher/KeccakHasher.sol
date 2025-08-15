// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract KeccakHasher {

    function repeatedKeccak256(int256 n) public pure returns (bytes32) {
        bytes32 currentHash = bytes32(0);

        for (uint256 i = 0; i < uint256(n); i++) {
            currentHash = keccak256(currentHash);
        }
        return currentHash;
    }
}