// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import "../../solidity/IHieroAccountAllowanceHook.sol";
import "../../solidity/IHieroHook.sol";

interface Target {
    function believeIn(uint32 no) external returns (uint32);
}

/// Hook that performs multiple nested contract calls (no precompiles) during allow().
/// allow() calls target.believeIn(1) and target.believeIn(2) on a separate contract,
/// producing 3 contract call records total: 1 for allow(), 2 for the nested calls.
/// EvmHookCall.data must be abi.encode(address target).
contract MultipleContractCallsHook is IHieroAccountAllowanceHook {
    // HIP-1195 special hook address (0x...016d padded to 20 bytes)
    address constant HOOK_ADDR = address(uint160(0x16d));

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory /* proposedTransfers */
    ) external payable override returns (bool) {
        require(address(this) == HOOK_ADDR, "Contract can only be called as a hook");

        address target = abi.decode(context.data, (address));

        // Nested contract call 1: call believeIn(1) on the target contract and use the result
        uint32 result1 = Target(target).believeIn(1);
        // Nested contract call 2: call believeIn(2) on the target contract and use the result
        uint32 result2 = Target(target).believeIn(2);
        require(result1 == 1 && result2 == 2, "believeIn result mismatch");

        return true;
    }
}
