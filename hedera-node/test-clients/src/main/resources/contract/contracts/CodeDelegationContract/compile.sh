#!/bin/bash

solcjs --bin --abi CodeDelegationContract.sol --include-path ../../solidity --base-path .
mv CodeDelegationContract_sol_CodeDelegationContract.bin CodeDelegationContract.bin
mv CodeDelegationContract_sol_CodeDelegationContract.abi CodeDelegationContract.json
