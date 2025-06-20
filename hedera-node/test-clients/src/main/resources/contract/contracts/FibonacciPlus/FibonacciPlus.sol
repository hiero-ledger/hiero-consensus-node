// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.0;

contract FibonacciPlus {
    uint256[] slots;

    constructor(uint32 numSlots) public {
        slots = new uint256[](numSlots);
    }

    function addNthFib(uint32[] memory at, uint32 n) public {
        uint256 fibN = fib(n);

        for (uint32 i = 0; i < at.length; i++) {
            slots[at[i]] += fibN;
        }
    }

    function fib(uint32 n) internal returns (uint256) {
        if (n <= 1) {
            return n;
        } else {
            return fib(n - 1) + fib(n - 2);
        }
    }

    function currentSlots() external view returns (uint256[] memory) {
        return slots;
    }
}
