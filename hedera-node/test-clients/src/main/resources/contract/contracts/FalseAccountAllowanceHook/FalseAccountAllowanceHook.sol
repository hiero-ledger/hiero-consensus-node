// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHieroAccountAllowanceHook.sol';

/// A degenerate hook useful for basic HIP-1195 testing
contract FalseAccountAllowanceHook is IHieroAccountAllowanceHook {
    function allow(
       IHieroHook.HookContext calldata context,
       ProposedTransfers memory proposedTransfers
    ) override external payable returns (bool) {
      return false;
    }
} 
