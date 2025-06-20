# System accounts

## Definition

The Hedera network reserves the first 1000 entity numbers for its own uses, and any account with a number in this reserved range is referred to as a system account.

The following addresses are reserved for the Hedera system contracts:
- 0x167 (0.0.359) - HTS (Hedera Token Service)
- 0x168 (0.0.360) - ExchangeRate;
- 0x169 (0.0.361) - PRNG;
- 0x16A (0.0.362) - HAS (Hedera Account Service).

Please note that this list can expand in the future.

Some of the system accounts exist today and more can be created in the future. Certain system accounts have predefined roles in the network. These include:
- 0.0.2 - treasury account;
- 0.0.50 - system admin;
- 0.0.55 - address book admin;
- 0.0.57 - exchange rates admin;
- 0.0.58 - freeze admin;
- 0.0.59 - system delete admin;
- 0.0.60 - system undelete admin;
- 0.0.800 - staking reward account;
- 0.0.801 - node reward account.

EVM addresses in range 1 → 9 are special addresses on which the Ethereum precompiled contracts exist. This is valid for both Ethereum and Hedera. Please note that this list can expand in the future.

Lastly, the system accounts can be divided in these two groups:
- addresses in range 0 → 750 - accounts that reject hbar transfers. An exception is the HTS system contract that can receive transfers, see [below](#hedera-transfer-hts).
- _But see the exception for a token create call to the HTS system contract at `0.0.359` below
- addresses in range 751 → 1000 - accounts which may receive transfers.

## Rationale

As a result of the specifics of the system accounts in Hedera described in the section above there are some differences when calling operations on system accounts in Hedera and in Ethereum via smart contract executions.
This doc covers these differences and describes the expected behavior.

## Operations

Legend of the symbols used in the tables below:
- ✅ - the operation was successful;
- ❌ - the operation failed;
- ❗ - something specific to note when executing an operation.

### ExtCode operations

`ExtCodeSize`

**Ethereum:** Get size of an account’s code. If there’s no code, it returns 0.

**Hedera:** For addresses in range 0x0 → 0x3E8 (0.0.0 → 0.0.1000) it returns 0.

`ExtCodeCopy`

**Ethereum:** Copy an account’s code to memory. If a contract doesn’t exist on the address, it copies empty bytes.

**Hedera:** For addresses in range 0x0 → 0x3E8 (0.0.0 → 0.0.1000) it copies empty bytes.

`ExtCodeHash`

**Ethereum:** Hash of the chosen account's code, the empty hash (0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470) if the account has no code, or 0 if the account does not exist or has been destroyed.

**Hedera:** For addresses in range 0x0 → 0x3E8 (0.0.0 → 0.0.1000) it returns 0x0000000000000000000000000000000000000000000000000000000000000000.

### Balance operation

**Ethereum:** Returns balance or 0 if an account or contract doesn’t exist on the address. This is valid for any address.

**Hedera:**
1. For addresses in range 0x0 → 0x2EE (0.0.0 → 0.0.750): returns 0 as if account doesn’t exist.
2. For addresses in range 0x2EF → 0x3E8 (0.0.751 → 0.0.1000): returns balance or 0 if an account or contract doesn’t exist on the address. This behavior is the same for addresses above 0.0.1000.

|              Address               |                                  in Ethereum                                  |                                   in Hedera                                   |
|------------------------------------|-------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| 0x0                                | ✅ returns balance or 0 if an account or contract doesn’t exist on the address | ✅ returns 0 as if account doesn’t exist                                       |
| 0x1 → 0x9                          | ✅ returns balance or 0 if an account or contract doesn’t exist on the address | ✅ returns 0 as if account doesn’t exist                                       |
| 0xA → 0x166                        | ✅ returns balance or 0 if an account or contract doesn’t exist on the address | ✅ returns 0 as if account doesn’t exist                                       |
| 0x167 (0.0.359)                    | ✅ returns balance or 0 if an account or contract doesn’t exist on the address | ✅ returns 0 as if account doesn’t exist                                       |
| 0x168, 0x169 (0.0.360, 0.0.361)    | ✅ returns balance or 0 if an account or contract doesn’t exist on the address | ✅ returns 0 as if account doesn’t exist                                       |
| 0x16a → 0x2EE (0.0.361 → 0.0.750)  | ✅ returns balance or 0 if an account or contract doesn’t exist on the address | ✅ returns 0 as if account doesn’t exist                                       |
| 0x2EF → 0x3E8 (0.0.751 → 0.0.1000) | ✅ returns balance or 0 if an account or contract doesn’t exist on the address | ✅ returns balance or 0 if an account or contract doesn’t exist on the address |
| 0x3E8 → ♾️                         | ✅ returns balance or 0 if an account or contract doesn’t exist on the address | ✅ returns balance or 0 if an account or contract doesn’t exist on the address |

### SelfDestruct operation

**Ethereum:**
- `SelfDestruct` executed in the same transaction that the contract was created - the contract data is deleted (storage keys, code, etc.), the account balance is transferred to the target. If the target is the same as the contract calling `SelfDestruct`, the Ether will be burnt.
- otherwise - all funds are recovered but any other account data is not deleted. The entire account balance is transferred to the target. If the target is the same as the contract calling `SelfDestruct`, there is no net change in the balances. Ether will not be burnt in this case.

**Hedera:**
1. For addresses in range 0x0 → 0x2EE (0.0.0 → 0.0.750): fail with status `INVALID_SOLIDITY_ADDRESS`.
2. For addresses in range 0x2EF → 0x3E8 (0.0.751 → 0.0.1000):
- success, if account exists and has `receiverSigRequired` == false;
- success, if account exists and has `receiverSigRequired` == true and account is `sender`;
- fail in some failing scenarios such as:
- beneficiary same as account-to-be-deleted - fail with status `SELF_DESTRUCT_TO_SELF`;
- contract-to-be-deleted is a token treasury - fail with status `CONTRACT_IS_TREASURY`;
- contract-to-be-deleted has a positive fungible token balance - fail with status `TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES`;
- contract-to-be-deleted owns NFTs - fail with status `CONTRACT_STILL_OWNS_NFTS`;
- beneficiary has `receiverSigRequired` == true, they are not the sender in the message frame - fail with status `INVALID_SIGNATURE`.
3. For addresses above 0.0.1000: the same as 2. but a hollow account would be created if the beneficiary doesn't exist.

|               Address                |                in Ethereum                 |                                                                                                 in Hedera                                                                                                 |
|--------------------------------------|:------------------------------------------:|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 0x0                                  |                     ✅                      | ❌ - INVALID_SOLIDITY_ADDRESS                                                                                                                                                                              |
| 0x1 → 0x9                            |                     ✅                      | ❌ - INVALID_SOLIDITY_ADDRESS                                                                                                                                                                              |
| 0xA → 0x166                          |                     ✅                      | ❌ - INVALID_SOLIDITY_ADDRESS                                                                                                                                                                              |
| 0x167 (0.0.359)                      |                     ✅                      | ❌ - INVALID_SOLIDITY_ADDRESS                                                                                                                                                                              |
| 0x168, 0x169 (0.0.360, 0.0.361)      |                     ✅                      | ❌ - INVALID_SOLIDITY_ADDRESS                                                                                                                                                                              |
| 0x16a → 0x2EE (0.0.362 → 0.0.750)    |                     ✅                      | ❌ - INVALID_SOLIDITY_ADDRESS                                                                                                                                                                              |
| 0x2EF → 0x3E8 (0.0.751 → 0.0.1000)   |                     ✅                      | ✅ - if account exists [0.0.800-0.0.999]<br/>❌ - INVALID_SOLIDITY_ADDRESS if account does not exist [0.0.751 - 0.0.799]<br/>❌ - some failing scenarios described above                                     |
| 0x3E8 → ♾️                           |                     ✅                      | ✅ - if account exists and has receiverSigRequired = false<br/>✅ - if (account exists) and (account has receiverSigRequired = true) and (account is sender)<br/>❌ - some failing scenarios described above |
| beneficiary same as contract address | ✅ burns the eth and destructs the contract | ❌ - fails with SELF_DESTRUCT_TO_SELF                                                                                                                                                                      |

### Call operations that do not transfer value (CallOp, DelegateCallOp, CallCodeOp, StaticCallOp)

_Please note that the expected behavior described in this section is valid if there is no `value` passed. If there is `value`, this falls in the next section ("Transfer and send operations")._

1. For address 0x0:
   - **Ethereum:** success with no op. There is no contract on this address. It is often associated with token burn & mint/genesis events and used as a generic null address.
   - **Hedera:** success with no op.
2. For address range 0x1 → 0x9:
   - **Ethereum:** success, the Ethereum precompiles exist on these addresses.
   - **Hedera:** success, the Ethereum precompiles exist on these addresses.
3. For address range 0xA → 0x166 (0.0.10 → 0.0.358):
   - **Ethereum:**
     - success, if the address is a contract, and we are using the correct ABI;
     - success with no op, if there is no contract;
     - fail, if the address is a contract, and we are **not** using the correct ABI.
   - **Hedera:** success with no op.
4. For addresses 0x167, 0x168, 0x169 (0.0.359, 0.0.360, 0.0.361):
   - **Ethereum:**
     - success, if the address is a contract, and we are using the correct ABI;
     - success with no op, if there is no contract;
     - fail, if the address is a contract, and we are **not** using the correct ABI.
   - **Hedera:** the HTS, ExchangeRate and PRNG system contracts exist on these addresses accordingly.
     - success, if we are using the correct ABI;
     - fail, if we are **not** using the correct ABI.
5. For address range 0x16A → 0x3E8 (0.0.362 → 0.0.1000):
   - **Ethereum:**
     - success, if the address is a contract, and we are using the correct ABI;
     - success with no op, if there is no contract;
     - fail, if the address is a contract, and we are **not** using the correct ABI.
   - **Hedera:** success with no op. (with all gas consumed)

|              Address               |                                                                                Calls in Ethereum (ABI)                                                                                |                                            Calls in Hedera (ABI)                                             |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| 0x0                                | ✅ - success with no op, there is no contract on this address                                                                                                                          | ✅ - success with no op                                                                                       |
| 0x1 → 0x9                          | ✅ - Ethereum precompiles                                                                                                                                                              | ✅ - Ethereum precompiles                                                                                     |
| 0xA → 0x166 (0.0.10 → 0.0.358)     | ✅ - if address is a contract and we’re using the correct ABI<br/>✅ - success with no op, if there is no contract<br/>❌ - if address is a contract and we’re not using the correct ABI | ✅ - success with no op                                                                                       |
| 0x167 (0.0.359)                    | ✅ - if address is a contract and we’re using the correct ABI<br/>✅ - success with no op, if there is no contract<br/>❌ - if address is a contract and we’re not using the correct ABI | ✅ - HTS system contract                                                                                      |
| 0x168, 0x169 (0.0.360, 0.0.361)    | ✅ - if address is a contract and we’re using the correct ABI<br/>✅ - success with no op, if there is no contract<br/>❌ - if address is a contract and we’re not using the correct ABI | ✅ - ExchangeRate and PRNG system contracts                                                                   |
| 0x16a → 0x2EE (0.0.362 → 0.0.750)  | ✅ - if address is a contract and we’re using the correct ABI<br/>✅ - success with no op, if there is no contract<br/>❌ - if address is a contract and we’re not using the correct ABI | ✅ - success with no op                                                                                       |
| 0x2EF → 0x3E8 (0.0.751 → 0.0.1000) | ✅ - if address is a contract and we’re using the correct ABI<br/>✅ - success with no op, if there is no contract<br/>❌ - if address is a contract and we’re not using the correct ABI | ✅ - success with no op                                                                                       |
| 0x3E8 → ♾️                         | ✅ - if address is a contract and we’re using the correct ABI<br/>✅ - success with no op, if there is no contract                                                                      | ✅ - if a contract exists and we’re using the correct ABI<br/>✅ - success with no op, if there is no contract |

### Transfer and send operations

**Ethereum:**
1. For addresses in range 0x0 → 0x9: success, value is locked.
2. For addresses 0xA and above:
- success, if address is a payable contract;
- success, if address is an account;
- success, if address is empty + creates the account;
- fail, if address is a non-payable contract.

**Hedera:** <a id="hedera-transfer-hts"/>
1. For addresses in range 0x0 → 0x2EE (0.0.0 → 0.0.750):
- Transfers using `.send` and `.transfer`: fail with status `INVALID_FEE_SUBMITTED`. **Exception: `tokenCreate` to the HTS system contract 0.0.359 (`0x167`) is allowed[^1].**
- Transfer of HTS tokens through the HTS system contract: fail with status `INVALID_RECEIVING_NODE_ACCOUNT`.
2. For addresses in range 0x2EF → 0x3E8 (0.0.751 → 0.0.1000):
- success, if account exists and has `receiverSigRequired` == false;
- success, if account exists and has `receiverSigRequired` == false and account is `sender`;

[^1]:  Transfer to the HTS system contract on create token methods (only) is allowed so that additional HAPI fees beyond the
contract's gas execution fee can be paid.  This can be necessary because the gas throttle limit (currently 15Mgas/sec) can
be exceeded when the HAPI token entity creation fee is converted to gas (thus preventing the transaction from being accepted).

_The account is not created if it doesn't exist._
3. For addresses above 0.0.1000:
- success, if the address is a payable contract;
- success, if address is an account with `receiverSigRequired` == false;
- success, if address is empty + creates the account;
- fail, if address is an account with `receiverSigRequired` == true;
- if the address is a non-payable contract:
- fail for transfers using `.send` and `.transfer`;
- success for transfer of HTS tokens through the HTS system contract.

|              Address               |                                                            Transfers in Ethereum (.send, .transfer)                                                             |                                                                                                             Transfers in Hedera (.send, .transfer)                                                                                                              |                                                                                                     Transfers of HTS tokens through the HTS system contract                                                                                                     |
|------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 0x0                                | ✅ - value is locked                                                                                                                                             | ❌ - INVALID_FEE_SUBMITTED                                                                                                                                                                                                                                       | ❌ - INVALID_RECEIVING_NODE_ACCOUNT                                                                                                                                                                                                                              |
| 0x1 → 0x9                          | ✅ - value is locked                                                                                                                                             | ❌ - INVALID_FEE_SUBMITTED                                                                                                                                                                                                                                       | ❌ - INVALID_RECEIVING_NODE_ACCOUNT                                                                                                                                                                                                                              |
| 0xA → 0x166 (0.0.10 → 0.0.358)     | ✅ - if address is a payable contract<br/>✅ - if address is an account<br/>✅ - if address is empty, creates account<br/>❌ - if address is a non-payable contract | ❌ - INVALID_FEE_SUBMITTED                                                                                                                                                                                                                                       | ❌ - INVALID_RECEIVING_NODE_ACCOUNT                                                                                                                                                                                                                              |
| 0x167 (0.0.359)                    | ✅ - if address is a payable contract<br/>✅ - if address is an account<br/>✅ - if address is empty, creates account<br/>❌ - if address is a non-payable contract | ✅ - Ok only for function tokenCreate(..)<br/>❌ - INVALID_FEE_SUBMITTED for all other function calls                                                                                                                                                             | ❌ - INVALID_RECEIVING_NODE_ACCOUNT                                                                                                                                                                                                                              |
| 0x168, 0x169 (0.0.360, 0.0.361)    | ✅ - if address is a payable contract<br/>✅ - if address is an account<br/>✅ - if address is empty, creates account<br/>❌ - if address is a non-payable contract | ❌ - INVALID_FEE_SUBMITTED                                                                                                                                                                                                                                       | ❌ - INVALID_RECEIVING_NODE_ACCOUNT                                                                                                                                                                                                                              |
| 0x16a → 0x2EE (0.0.362 → 0.0.750)  | ✅ - if address is a payable contract<br/>✅ - if address is an account<br/>✅ - if address is empty, creates account<br/>❌ - if address is a non-payable contract | ❌ - INVALID_FEE_SUBMITTED                                                                                                                                                                                                                                       | ❌ - INVALID_RECEIVING_NODE_ACCOUNT                                                                                                                                                                                                                              |
| 0x2EF → 0x3E8 (0.0.751 → 0.0.1000) | ✅ - if address is a payable contract<br/>✅ - if address is an account<br/>✅ - if address is empty, creates account<br/>❌ - if address is a non-payable contract | ✅ - if account exists and has receiverSigRequired = false<br/>✅ - if (account exists) and (account has receiverSigRequired = true) and (account is sender)<br/>❗- don’t auto create if it doesn’t exist                                                         | ✅ - if account exists and has receiverSigRequired = false<br/>✅ - if (account exists) and (account has receiverSigRequired = true) and (account is sender)<br/>❗- don’t auto create if it doesn’t exist                                                         |
| 0x3E8 → ♾️                         | ✅ - if address is a payable contract<br/>✅ - if address is an account<br/>✅ - if address is empty, creates account<br/>❌ - if address is a non-payable contract | ✅ - if address is a payable contract<br/>✅ - if address is an account with receiverSigRequired = false<br/>✅ - if address is empty, creates account<br/>❌ - if address is a non-payable contract<br/>❌ - if address is an account with receiverSigRequired=true | ✅ - if address is a payable contract<br/>✅ - if address is an account with receiverSigRequired = false<br/>✅ - if address is empty, creates account<br/>✅ - if address is a non-payable contract<br/>❌ - if address is an account with receiverSigRequired=true |
