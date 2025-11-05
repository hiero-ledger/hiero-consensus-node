// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHieroAccountAllowanceHook.sol';

/// Minimal pre-hook: allow only if memo contains "Please".
contract PleasePreHook is IHieroAccountAllowanceHook {
    // HIP-1195 special hook address (0x...016d)
    address constant HOOK_ADDR = address(uint160(0x16d));

    function allow(
        IHieroHook.HookContext calldata ctx,
        ProposedTransfers memory /*unused*/
    ) external payable override returns (bool) {
        require(address(this) == HOOK_ADDR, "hook only");
        return _contains(bytes(ctx.memo), bytes("Please"));
    }

    function _contains(bytes memory h, bytes memory n) private pure returns (bool) {
        uint hl = h.length;
        uint nl = n.length;
        if (nl == 0) return true;
        if (nl > hl) return false;
        for (uint i; i + nl <= hl; ) {
            uint j;
            while (j < nl && h[i + j] == n[j]) {
                unchecked { ++j; }
            }
            if (j == nl) return true;
            unchecked { ++i; }
        }
        return false;
    }
}