// SPDX-License-Identifier: MIT
pragma solidity ^0.8.19;

/**
 * Test helper that exercises the MODEXP precompile's ops-duration accounting path.
 *
 * feedSaturatedModExp calls MODEXP (address 0x05) with maximal length fields, so its reported gas
 * requirement exceeds what the call can afford and the inner call halts for insufficient gas. The
 * wrapper returns that outcome as a boolean, so the outer call still succeeds. readOne is an ordinary,
 * cheap call used as a control.
 */
contract OpsDurationPrecompileFeed {
    function feedSaturatedModExp() external returns (bool success) {
        bytes memory input = new bytes(96);
        assembly {
            mstore(add(input, 0x20), 0x7ffffffffffffff8)
            mstore(add(input, 0x40), 1)
            mstore(add(input, 0x60), 0x7ffffffffffffff8)
            success := call(gas(), 0x05, 0, add(input, 0x20), 0x60, 0, 0)
        }
    }

    function readOne() external pure returns (uint256) {
        return 1;
    }
}