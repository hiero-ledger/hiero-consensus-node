// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHieroAccountAllowancePrePostHook.sol';

/// A degenerate hook useful for basic HIP-1195 testing
contract FalseTruePrePostHook is IHieroAccountAllowancePrePostHook {
    function allowPre(
        IHieroHook.HookContext calldata context,
        IHieroAccountAllowanceHook.ProposedTransfers memory proposedTransfers
    ) override external payable returns (bool){
        return false;
    }

    function allowPost(
        IHieroHook.HookContext calldata context,
        IHieroAccountAllowanceHook.ProposedTransfers memory proposedTransfers
    ) override external payable returns (bool){
        return true;
    }
} 
