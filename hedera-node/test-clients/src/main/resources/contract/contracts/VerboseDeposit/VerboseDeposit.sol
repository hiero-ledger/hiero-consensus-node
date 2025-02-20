// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.3;

contract VerboseDeposit {
	event DepositMemo(string s);

	function deposit(
		uint32 amount, 
		uint32 timesForEmphasis, 
		string memory memo
	) payable public returns(string memory outcome) {
		require(msg.value == amount);
		bytes memory toUse = bytes(memo);
		bytes memory result = new bytes(timesForEmphasis * toUse.length);
		while (timesForEmphasis > 0) {
			emit DepositMemo(memo);
			timesForEmphasis--;
			for (uint i = 0; i < toUse.length; i++) {
				uint j = (timesForEmphasis * toUse.length) + i;
				result[j] = toUse[i];
			}
		}
		outcome = string(result);
	}
}
