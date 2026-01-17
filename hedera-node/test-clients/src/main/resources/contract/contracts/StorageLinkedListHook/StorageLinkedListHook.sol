// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import '../../solidity/IHieroAccountAllowanceHook.sol';

/// A hook that mutates multiple simple storage slots to exercise
/// HIP-1195 linked-list storage semantics via normal SSTOREs.
///
/// It interprets the first byte of `context.data` as an opcode:
/// 0x01 = write zero to an empty slot (s0 = 0) -> should not change slot count
/// 0x02 = populate three slots with non-zero values (s0,s1,s2)
/// 0x03 = remove all existing slots (s0=s1=s2=0)
/// 0x04 = add and then remove all three slots within the same call
contract StorageLinkedListHook is IHieroAccountAllowanceHook {
    // Three simple storage slots at indices 0,1,2
    bytes32 s0;
    bytes32 s1;
    bytes32 s2;

    // HIP-1195 special hook address (0x...016d padded to 20 bytes)
    address constant HOOK_ADDR = address(uint160(0x16d));

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external override payable returns (bool) {
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");
        bytes calldata data = context.data;
        uint8 op = data.length == 0 ? 0 : uint8(data[0]);

        if (op == 0x01) {
            // Zero into an empty slot: does an SSTORE of 0 into an untouched slot
            s0 = bytes32(0);
        } else if (op == 0x02) {
            // Populate three slots with non-zero values
            s0 = keccak256(abi.encodePacked("a"));
            s1 = keccak256(abi.encodePacked("b"));
            s2 = keccak256(abi.encodePacked("c"));
        } else if (op == 0x03) {
            // Remove all existing slots
            s0 = bytes32(0);
            s1 = bytes32(0);
            s2 = bytes32(0);
        } else if (op == 0x04) {
            // Add then remove within the same call
            s0 = bytes32(uint256(1));
            s1 = bytes32(uint256(2));
            s2 = bytes32(uint256(3));
            s0 = bytes32(0);
            s1 = bytes32(0);
            s2 = bytes32(0);
        }
        // Always allow the transfer for these tests
        return true;
    }
}

