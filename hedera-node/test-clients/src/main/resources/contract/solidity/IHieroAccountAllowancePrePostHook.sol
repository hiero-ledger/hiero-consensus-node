// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHieroHook.sol';
import './IHieroAccountAllowanceHook.sol';

/// The interface for an account allowance hook invoked both before and after a CryptoTransfer.
interface IHieroAccountAllowancePrePostHook {
   /// Decides if the proposed transfers are allowed BEFORE the CryptoTransfer
   /// business logic is performed, optionally in the presence of additional
   /// context encoded by the transaction payer in the extra calldata.
   /// @param context The context of the hook call
   /// @param proposedTransfers The proposed transfers
   /// @return true If the proposed transfers are allowed, false or revert otherwise
   function allowPre(
      IHieroHook.HookContext calldata context,
      IHieroAccountAllowanceHook.ProposedTransfers memory proposedTransfers
   ) external payable returns (bool);

   /// Decides if the proposed transfers are allowed AFTER the CryptoTransfer
   /// business logic is performed, optionally in the presence of additional
   /// context encoded by the transaction payer in the extra calldata.
   /// @param context The context of the hook call
   /// @param proposedTransfers The proposed transfers
   /// @return true If the proposed transfers are allowed, false or revert otherwise
   function allowPost(
      IHieroHook.HookContext calldata context,
      IHieroAccountAllowanceHook.ProposedTransfers memory proposedTransfers
   ) external payable returns (bool);
}
