// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

contract OpsDurationDynamicOpcodes {

    // -----------------------------------------------------------------------
    // 1. KECCAK256 (0x20) — 30 + 6 × ⌈size / 32⌉
    // -----------------------------------------------------------------------

    /**
     * @notice Allocates `length` bytes of zeroed memory and hashes them with KECCAK256.
     * @param length Number of bytes to hash.
     * @return hash The resulting keccak256 digest.
     */
    function benchKeccak256(uint256 length) external pure returns (bytes32 hash) {
        assembly {
            let ptr := mload(0x40)          // free memory pointer
            mstore(0x40, add(ptr, length))  // advance free memory pointer
            hash := keccak256(ptr, length)
        }
    }

    // -----------------------------------------------------------------------
    // 2. CALLDATACOPY (0x37) — 3 + 3 × ⌈size / 32⌉ + mem expansion
    // -----------------------------------------------------------------------

    /**
     * @notice Copies `length` bytes from calldata into memory using CALLDATACOPY.
     * @param length Number of bytes to copy (capped to actual calldata size).
     */
    function benchCalldatacopy(uint256 length) external pure {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            calldatacopy(ptr, 0, length)
        }
    }

    // -----------------------------------------------------------------------
    // 3. CODECOPY (0x39) — 3 + 3 × ⌈size / 32⌉ + mem expansion
    // -----------------------------------------------------------------------

    /**
     * @notice Copies `length` bytes of this contract's own bytecode into memory.
     * @param length Number of bytes to copy.
     */
    function benchCodecopy(uint256 length) external pure {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            codecopy(ptr, 0, length)
        }
    }

    // -----------------------------------------------------------------------
    // 4. EXTCODECOPY (0x3C) — 100/2600 + 3 × ⌈size / 32⌉ + mem expansion
    // -----------------------------------------------------------------------

    /**
     * @notice Copies `length` bytes from this contract's own bytecode via EXTCODECOPY.
     * @param length Number of bytes to copy.
    */
    function benchExtcodecopy(uint256 length) external view {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            extcodecopy(address(), ptr, 0, length)
        }
    }

    // -----------------------------------------------------------------------
    // 5. RETURNDATACOPY (0x3E) — 3 + 3 × ⌈size / 32⌉ + mem expansion
    // -----------------------------------------------------------------------

    /**
     * @notice Returns exactly `length` zero bytes — used to populate the return buffer
     *         for RETURNDATACOPY benchmarking.
     * @param length Number of bytes to return.
     */
    function _returnNBytes(uint256 length) external pure {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            return (ptr, length)
        }
    }

    /**
     * @notice Makes an external call that returns `length` bytes, then copies all of it
     *         via RETURNDATACOPY — gas scales with length.
     * @param length Number of bytes to copy from the return buffer.
     */
    function benchReturndatacopy(uint256 length) external view {
        bytes memory callData = abi.encodeWithSelector(this._returnNBytes.selector, length);
        assembly {
            let success := staticcall(gas(), address(), add(callData, 0x20), mload(callData), 0, 0)
            // returndatasize() is now exactly `length`
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            returndatacopy(ptr, 0, length)
        }
    }

    // -----------------------------------------------------------------------
    // 6. LOG0 (0xA0) — 375 + 8 × size
    // -----------------------------------------------------------------------

    /**
     * @notice Emits a LOG0 (no topics) with `length` bytes of data.
     * @param length Number of bytes to include in the log payload.
     */
    function benchLog0(uint256 length) external {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            log0(ptr, length)
        }
    }

    // -----------------------------------------------------------------------
    // 7. LOG1 (0xA1) — 375 + 8 × size + 375
    // -----------------------------------------------------------------------

    /**
     * @notice Emits a LOG1 (1 topic) with `length` bytes of data.
     * @param length Number of bytes to include in the log payload.
     */
    function benchLog1(uint256 length) external {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            log1(ptr, length, 0x1111111111111111111111111111111111111111111111111111111111111111)
        }
    }

    // -----------------------------------------------------------------------
    // 8. LOG2 (0xA2) — 375 + 8 × size + 750
    // -----------------------------------------------------------------------

    /**
     * @notice Emits a LOG2 (2 topics) with `length` bytes of data.
     * @param length Number of bytes to include in the log payload.
     */
    function benchLog2(uint256 length) external {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            log2(ptr, length,
                0x1111111111111111111111111111111111111111111111111111111111111111,
                0x2222222222222222222222222222222222222222222222222222222222222222)
        }
    }

    // -----------------------------------------------------------------------
    // 9. LOG3 (0xA3) — 375 + 8 × size + 1125
    // -----------------------------------------------------------------------

    /**
     * @notice Emits a LOG3 (3 topics) with `length` bytes of data.
     * @param length Number of bytes to include in the log payload.
     */
    function benchLog3(uint256 length) external {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            log3(ptr, length,
                0x1111111111111111111111111111111111111111111111111111111111111111,
                0x2222222222222222222222222222222222222222222222222222222222222222,
                0x3333333333333333333333333333333333333333333333333333333333333333)
        }
    }

    // -----------------------------------------------------------------------
    // 10. LOG4 (0xA4) — 375 + 8 × size + 1500
    // -----------------------------------------------------------------------

    /**
     * @notice Emits a LOG4 (4 topics) with `length` bytes of data.
     * @param length Number of bytes to include in the log payload.
     */
    function benchLog4(uint256 length) external {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            log4(ptr, length,
                0x1111111111111111111111111111111111111111111111111111111111111111,
                0x2222222222222222222222222222222222222222222222222222222222222222,
                0x3333333333333333333333333333333333333333333333333333333333333333,
                0x4444444444444444444444444444444444444444444444444444444444444444)
        }
    }

    // -----------------------------------------------------------------------
    // 11. CREATE (0xF0) — 32000 + 200 × code_size + 3 × ⌈init_code / 32⌉
    // -----------------------------------------------------------------------

    /**
     * @notice Deploys a contract whose init code is `length` bytes of STOP (0x00).
     * @param length Size of the init bytecode in bytes.
     * @return deployed Address of the newly deployed contract (may be zero on failure).
     */
    function benchCreate(uint256 length) external returns (address deployed) {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            // init code is `length` zero bytes — all STOP opcodes, returns empty runtime
            deployed := create(0, ptr, length)
        }
    }

    // -----------------------------------------------------------------------
    // 12. CREATE2 (0xF5) — 32000 + 200 × code_size + 6 × ⌈init_code / 32⌉
    // -----------------------------------------------------------------------

    /**
     * @notice Deploys a contract via CREATE2 with `length` bytes of init code.
     * @param length Size of the init bytecode in bytes.
     * @return deployed Address of the newly deployed contract (may be zero on failure).
     */
    function benchCreate2(uint256 length) external returns (address deployed) {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            deployed := create2(0, ptr, length, 0x0)
        }
    }

    // -----------------------------------------------------------------------
    // 13. RETURN (0xF3) — memory expansion only
    // -----------------------------------------------------------------------

    /**
     * @notice Returns `length` zero bytes, exercising memory expansion for the
     *         return data region.
     * @param length Number of bytes to return.
     */
    function benchReturn(uint256 length) external pure {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            return (ptr, length)
        }
    }

    // -----------------------------------------------------------------------
    // 14. REVERT (0xFD) — memory expansion only
    // -----------------------------------------------------------------------

    /**
     * @notice Reverts with `length` zero bytes of reason data, exercising memory
     *         expansion for the revert data region.
     * @param length Number of bytes to include in the revert payload.
     */
    function benchRevert(uint256 length) external pure {
        assembly {
            let ptr := mload(0x40)
            mstore(0x40, add(ptr, length))
            revert(ptr, length)
        }
    }
}