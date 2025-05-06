---

hip: <HIP number (assigned by the HIP editor)>
title: Hiero hooks and an application to allowances
author: Michael Tinker <@tinker-michaelj>
working-group: Richard Bair <@rbair23>, Jasper Potts <@jasperpotts>, Atul Mahamuni <atul@hashgraph.com>,  Matthew DeLorenzo <@littletarzan>, Edward Wertz <@edward-swirldslabs>, Steven Sheehy <@steven-sheehy>
requested-by: Hashgraph
type: Standards Track
category: Service
needs-council-approval: Yes
status: Draft
created: 2025-02-19
discussions-to: <TODO>
updated: 2025-02-19
-------------------

## Abstract

We propose **hooks**, programmable Hiero extension points that let users customize the behavior of their entities.
In principle, hooks could be programmed in any language, but we begin with **EVM hooks**. Users program EVM hooks 
by writing contracts in a language like Solidity that compiles to EVM bytecode. EVM hooks are either **pure**
(using neither storage nor external contracts); or **lambdas** (like code running in a cloud's event-driven compute
offering, which may access a database to use state or call other external services). Users can install multiple
hooks at different **indexes** on the same entity, until they reach a configured limit.

As a first Hiero extension point, we propose **allowance hooks**. Users can install these hooks on their accounts.
A Hiero API (HAPI) `CryptoTransfer` transaction can then reference a hook allowance just as it does an ERC-style
allowance defined in [HIP-376](https://hips.hedera.com/hip/hip-376). The network uses the hook by calling its EVM bytecode at a specific function 
signature, passing in the details of the transfers proposed in the transaction. If the hook returns `true`, the network 
continues executing the transfer; otherwise the network rejects the transfer. Installing a hook on an account is 
analogous to adding a function to a smart contract; the hook executes with the account's privileges when calling 
Hedera system contracts, just as a smart contract's functions do.

Unlike smart contracts, which must encapsulate trust guarantees for multiple parties, lambdas belong to a single
owner who can directly update their storage via a `LambdaSStore` transaction. This streamlined design supports
fast, low-cost adjustments to a lambda with less overhead than a typical `ConsensusSubmitMessage`; and far less
than a `ContractCall`.

## Motivation

Hedera users often want to customize native entities instead of migrating their decentralized applications (dApps) to
purely EVM-based smart contracts. Consider the following examples:
- [HIP-18: Custom Hedera Token Service Fees](https://hips.hedera.com/hip/hip-18) introduced custom fee
payments for HTS transfers.
- [HIP-904: Frictionless Airdrops](https://hips.hedera.com/hip/hip-904) enabled more permissive token association
policies.
- [HIP-991: Permissionless revenue-generating Topic Ids for Topic Operators](https://hips.hedera.com/hip/hip-991)
proposed fee-based access control for message submissions to topics.

Hooks provide a more general solution to the problem of users needing custom business logic for their entities. For
example, a token issuer might need to enforce rules on every transaction involving their token for regulatory or
business reasons. A **transfer hook** installable on token types could enforce these rules without requiring the
issuer to take a full design proposal through the HIP process, _or_ lose the performance and simplicity of the native
APIs by moving everything into a smart contract.

That is, by avoiding protocol-level changes for every important customization, hooks can greatly streamline innovation
on a Hiero network while maintaining the performance and integrity of the native services.

## Specification

First we specify how a Hiero network will charge, throttle, and execute EVM hooks. (Non-EVM hook programming models 
would need their own specifications.)

The protobuf API for hooks in general, and EVM hooks in particular, follows in later sections.

### Charging

A primary concern for EVM hooks is deciding what account pays for the EVM gas upfront before executing the hook. We
propose two charging patterns that should accommodate most use cases. The **installer** of an EVM hook chooses one of
the patterns at the time they install it.
1. `CALLER_PAYS` - The payer of the transaction that triggers the hook pays for its upfront gas cost. They receive the
normal refund for unused gas.
2. `CALLER_PAYS_ON_REVERT` - The payer of the triggering transaction again pays for the upfront gas cost, but receives
a _full refund_ if the hook succeeds (in sense appropriate to the extension point). On success, a designated account
that signed the lambda's installation pays for the gas actually consumed.

Regardless of the charging pattern, a triggering transaction can always set an explicit gas limit for an EVM hook's
execution, just as with a contract call. If no explicit limit is set on the transaction, the networks checks if the
EVM was installed with a default gas limit. If there is no default limit for the hook, the network uses a global
default, for example `hooks.evm.defaultGasLimit=25_000`.

We propose the same gas price for EVM hook execution as for other contract operations. However, unlike contract calls,
which are charged purely in gas, hook executions are already "gated" by the fee of their triggering transaction.
So it makes sense to waive the intrinsic gas cost of their execution. We propose adding two more properties,

```
hooks.evm.pureIntrinsicGasCost=0
hooks.evm.lambdaIntrinsicGasCost=0
```

### Throttling

We propose EVM hooks be subject to the same gas throttle as top-level contract calls. Specifically, when an EVM hook
executes, its initial EVM sender address is the payer of the referencing transaction. If this payer is throttle exempt,
no throttles are applied. Otherwise, if the network is at capacity for gas usage, EVM hook execution will be throttled
on that basis and the triggering transaction fail with a status of `EVM_HOOK_EXECUTION_THROTTLED`.

### EVM hook execution

The first message frame for the EVM transaction that executes an EVM hook will be a logical `DELEGATECALL` from the
**hook system contract address `0x16d`** to the hook's implementing `ContractID`, sent by the payer of the upfront gas
cost for the hook's execution.

As a concrete example, suppose account `0.0.A` installs an EVM lambda hook for the `ACCOUNT_ALLOWANCE_HOOK` extension
point at index `1`. The hook's implementing contract is `0.0.H` with EVM address `0xab...cd`. Now `0.0.B` with EVM
address `0x01...23` sends a `CryptoTransfer` transaction that references the hook `0.0.A.1` with gas limit `100_000`.

The network will construct an initial EVM message frame with,
1. `sender` address `0x01...23`;
2. `receiver` address `0x16d`;
3. `contract` address `0xab...cd` (hence the source of the executing bytecode);
4. Storage of the `0.0.A.1` lambda EVM hook; and,
5. Gas remaining of `100_000` (no intrinsic gas cost).

Since this hook is a lambda, it can then proceed with arbitrary EVM behaviors, including calls to external oracles,
`SLOAD` and `SSTORE` operations with its storage, and so on.

If the `0.0.A.1` hook is a pure EVM hook, it executes in an extremely restricted mode. Not only is the initial frame
marked **static**, prohibiting all state-changing operations; but the network also disables the `PREVRANDAO`, `SLOAD`,
and `CALL` opcodes. That is, a pure hook cannot do anything but apply a pure function to its input data.

Although users will doubtless uncover a very wide range of use cases for EVM hooks, we expect the single most common
type of hook contract to implement a single external method that reverts when not executed by the network _as a hook_.
That is,

```solidity
contract HookContract {
    function hookFunction(address installer) external payable {
        // Revert if we are not executing a DELEGATECALL from the hook system contract
        require(address(this) == 0x16d, "Contract can only be called as a hook");
        // Continue executing as a hook on behalf of the installer address
    }
}
```

Because a hook is conceptually a function of the installing account, when it calls a Hedera system contract, the system 
contract will see that account's address as the sender. If a hook calls another contract, it is valid for that contract 
to call back to the hook at address `0x16d`.

### Core HAPI protobufs

A hook's extension point is one of an enumeration which now includes only the account allowance hook,
```protobuf
/***
 * The Hiero extension points that accept a hook.
 */
enum HookExtensionPoint {
  /**
   * Used to customize an account's allowances during a CryptoTransfer transaction.
   */
  ACCOUNT_ALLOWANCE_HOOK = 0;
}
```

Users install hooks by setting a `HookInstall` field on an appropriate create or update transaction. This message is,

```protobuf
/***
 * How to install a hook.
 */
message HookInstall {
  /**
   * The extension point for the hook.
   */
  HookExtensionPoint extension_point = 1;

  /**
   * The entity index to install the hook at.
   */
  uint64 index = 2;

  /**
   * The hook implementation.
   */
  oneof hook {
    /**
     * A hook programmed in EVM bytecode that does not require access to state
     * or interactions with external contracts.
     */
    PureEvmHook pure_evm_hook = 3;
    /**
     * A hook programmed in EVM bytecode that may access state or interact with
     * external contracts.
     */
    LambdaEvmHook lambda_evm_hook = 4;
  }

  /**
   * If set, a key that that can be used to remove or replace the hook; or (if
   * applicable, as with a lambda EVM hook) perform transactions that customize
   * the hook.
   */
  proto.Key admin_key = 5;

  /**
   * The charging pattern for the hook.
   */
  oneof charging_spec {
    /**
     * The payer of the transaction calling the hook pays for its execution.
     */
    CallerPays caller_pays = 6;
    /**
     * The payer of the transaction calling the hook pays for its execution if
     * the hook fails; otherwise, a specified payer pays.
     */
    CallerPaysOnFailure caller_pays_on_failure = 7;
  }
}
```

The `PureEvmHook` and `LambdaEvmHook` messages share a common `EvmHookSpec` message that specifies the source of the
hook's EVM bytecode and a default gas limit for its execution. The `LambdaEvmHook` message also includes the initial
storage slots for a lambda hook, if desired.

```protobuf
/**
 * Definition of a lambda EVM hook.
 */
message PureEvmHook {
  /**
   * The specification for the hook.
   */
  EvmHookSpec spec = 1;
}

/**
 * Definition of a lambda EVM hook.
 */
message LambdaEvmHook {
  /**
   * The specification for the hook.
   */
  EvmHookSpec spec = 1;

  /**
   * Initial storage contents for the lambda, if any.
   */
  repeated LambdaStorageSlot storage_slots = 2;
}

/**
 * Shared specifications for an EVM hook. May be used for any extension point.
 */
message EvmHookSpec {
  /**
   * The source of the EVM bytecode for the hook.
   */
  oneof bytecode_source {
    /**
     * The id of a contract that implements the extension point API with EVM bytecode.
     */
    ContractID contract_id = 1;
  }

  /**
   * If present, the default gas limit to use when executing the EVM hook.
   */
  google.protobuf.UInt64Value default_gas_limit = 2;
}

/**
 * A slot in the storage of a lambda EVM hook.
 */
message LambdaStorageSlot {
  /**
   * The 32-byte key of the slot; leading zeros may be omitted.
   */
  bytes key = 1;

  /**
   * If the slot is present and non-zero, the 32-byte value of the slot;
   * leaving this field empty or setting it to binary zeros in an update
   * removes the slot.
   */
  bytes value = 2;
}
```

The indexes of newly installed hooks will appear in the legacy `TransactionReceipt` if records streams are enabled,

```protobuf

message TransactionReceipt {
  // ...

  /**
   * In the receipt of a successful create or update transaction for an entity that supports hooks,
   * the indexes of any newly installed hooks.
   */
  repeated uint64 installed_hook_indexes = 16;
}
```

Once a hook is installed to an entity, a transaction generally references it by index relative to an implicit owner.
The details of the call are specified based on its type; for example, EVM hook calls are specified by an `EvmHookCall`
message that gives optional call data and gas limit.

If the called hook does not match the given call specification, the network will fail the transaction with
`BAD_HOOK_REQUEST`. If there is no hook installed at the specified index, the network will fail the transaction
with `HOOK_NOT_FOUND`.

```protobuf
/**
 * Specifies a call to a hook from within a transaction where
 * the hook owner is implied by the point of use. (For example,
 * it would never make sense to try to use an account allowance
 * hook for account 0.0.X inside an AccountAmount for account
 * 0.0.Y; hence we only need to give the index of which of
 * 0.0.Y's hooks we want to call.)
 */
message HookCall {
  /**
   * The index of the hook to call.
   */
  uint64 index = 1;

  /**
   * Specifies details of the call.
   */
  oneof call_spec {
    /**
     * Specification of how to call an EVM hook.
     */
    EvmHookCall evm_hook_call = 2;
  }
}

/**
 * Specifies details of a call to an EVM hook.
 */
message EvmHookCall {
  /**
   * Extra call data to pass.
   */
  bytes evm_call_data = 1;

  /**
   * If set, an explicit gas limit to use.
   */
  google.protobuf.UInt64Value gas_limit = 3;
}
```

### Core system protobufs

Once a hook is installed, it has an id in the network state.

```protobuf
/**
 * Once a hook is installed, its id.
 */
message HookId {
  /**
   * The id of the hook's installer.
   */
  HookInstallerId installer_id = 1;

  /**
   * A unique identifier for the hook given the installer.
   */
  uint64 index = 2;
}

/**
 * The id of an entity that has installed a hook.
 */
message HookInstallerId {
  oneof installer_id {
    /**
     * An account installing a hook.
     */
    AccountID account_id = 1;
  }
}
```

EVM hooks will be implemented by internal dispatch from each installing entity type's service to the `ContractService`.
(A hook with a different programming model would require very different implementation details, so we restrict our
attention to EVM hooks.)

The dispatch for installing, executing, and uninstalling EVM hooks is a new `HookDispatchTransactionBody` with a choice
of three actions.

```protobuf
/**
 * Dispatches a hook action to an appropriate service.
 */
message HookDispatchTransactionBody {
  oneof action {
    /**
     * The id of the hook to uninstall.
     */
    HookId hook_id_to_uninstall = 1;

    /**
     * The installation of a new hook.
     */
    HookInstallation installation = 2;

    /**
     * An execution of an installed hook.
     */
    HookExecution execution = 3;
  }
}

/**
 * Specifies the execution of a hook by its installer id and
 * the details of the call (which includes the index).
 */
message HookExecution {
  /**
   * The id of the hook's installer.
   */
  HookInstallerId installer_id = 1;

  /**
   * The call details.
   */
  HookCall call = 2;
}
```

Since a pure EVM hook by definition has no call trace or storage access, its execution has no footprint in the block
stream. Executing a lambda EVM hook, however, produces `ContractCall` block items (`EventTransaction`,
`TransactionResult`, `TransactionOutput`) as following children of the triggering transaction, in the order of each
executed lambda.

When an EVM hook is installed, its representation in `ContractService` state is as follows,

```protobuf
/**
 * The representation of a lambda in state, including the previous and next indexes of its owner's lambda list.
 */
message EvmHookState {
  /**
   * For state proofs, the id of this hook.
   */
  proto.HookId hook_id = 1;

  /**
   * The type of the hook.
   */
  EvmHookType type = 2;

  /**
   * The type of the extension point the hook implements.
   */
  proto.HookExtensionPoint extension_point = 3;

  /**
   * The id of the contract with this hook's bytecode.
   */
  proto.ContractID hook_contract_id = 4;

  /**
   * The charging pattern of the hook.
   */
  proto.HookChargingSpec charging_spec = 5;

  /**
   * If set, the default gas limit to use when executing the lambda.
   */
  google.protobuf.UInt64Value default_gas_limit = 6;

  /**
   * True if the hook has been removed.
   */
  bool deleted = 7;

  /**
   * For a lambda EVM hook, its first storage key.
   */
  bytes first_contract_storage_key = 8;

  /**
   * If non-zero, the index of the hook preceding this one in the owner's
   * doubly-linked list of hook.
   */
  uint64 previous_index = 9;

  /**
   * If non-zero, the index of the hook following this one in the owner's
   * doubly-linked list of hooks.
   */
  uint64 next_index = 10;

  /**
   * The number of storage slots a lambda EVM hook is using.
   */
  uint32 num_storage_slots = 11;
}

/**
 * The type of an EVM hook.
 */
enum EvmHookType {
  /**
   * A pure EVM hook.
   */
  PURE = 0;
  /**
   * A lambda EVM hook.
   */
  LAMBDA = 1;
}
```

And its storage is keyed by the following type,

```protobuf
/**
 * The key of a lambda's storage slot.
 *
 * For each lambda, its EVM storage is a mapping of 256-bit keys (or "words")
 * to 256-bit values.
 */
message LambdaSlotKey {
  /**
   * The id of the lambda EVM hook that owns this slot.
   */
  proto.HookId hook_id = 1;

  /**
   * The EVM key of this slot, left-padded with zeros to form a 256-bit word.
   */
  bytes key = 2;
}
```

### Account allowance HAPI protobufs

The account allowance extension point is the only extension point defined in this HIP. Hooks for this extension are
installed on an account via either a `CryptoCreate` or `CryptoUpdate` transaction. That is, we extend the
`CryptoCreateTransactionBody` with a `hook_installs` field, and the `CryptoUpdateTransactionBody` with fields to
install and uninstall hooks.

```protobuf
message CryptoCreateTransactionBody {
  // ...

  /**
   * The hook installs to run immediately after creating this account.
   */
  repeated HookInstall hook_installs = 19;
}

message CryptoUpdateTransactionBody {
  // ...

  /**
   * The hooks to install on the account.
   */
  repeated HookInstall hook_installs = 19;

  /**
   * The indexes of the hooks to uninstall from the account.
   */
  repeated uint64 hook_indexes_to_uninstall = 20;
}
```

The `Account` message in `TokenService` state is extended to include the number of installed hooks, as well as
the indexes of the first last hooks in the doubly-linked list of hooks installed by the account.

```protobuf
message Account {
  // ...
  /**
   * The number of hook currently installed on this account.
   */
  uint64 number_installed_hooks = 36;

  /**
   * If positive, the index of the first hook installed on this account.
   */
  uint64 first_hook_index = 37;
}
```

For a successful such `CryptoCreate` or `CryptoUpdate`, the indexes of the newly installed hooks will appear in the
legacy record `TransactionReceipt` if record streams are still enabled.

Now we need to let a `CryptoTransfer` reference such a hook. For this we extend the `AccountAmount` and `NftTransfer`
messages used in the `CryptoTransferTransactionBody`.

```protobuf
message AccountAmount {
  // ...
  /**
   * If set, a call to a hook of type `ACCOUNT_ALLOWANCE_HOOK` installed on
   * accountID that must succeed for the transaction to occur.
   */
  HookCall allowance_hook_call = 4;
}

message NftTransfer {
  // ...
  /**
   * If set, a call to a hook of type `ACCOUNT_ALLOWANCE_HOOK` installed on
   * senderAccountID that must succeed for the transaction to occur.
   */
  HookCall sender_allowance_hook_call = 5;

  /**
   * If set, a call to a hook of type `ACCOUNT_ALLOWANCE_HOOK` installed on
   * receiverAccountID that must succeed for the transaction to occur.
   */
  HookCall receiver_allowance_hook_call = 6;
}
```

Note that `NftTransfer` supports both sender and receiver transfer allowance hooks, since the transaction may
need to use the receiver hook to satisfy a `receiver_sig_required=true` setting.

### The transfer allowance ABI

The account allowance EVM hook ABI is as follows,

```solidity
// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.4.9 <0.9.0;
pragma experimental ABIEncoderV2;

import './IHederaTokenService.sol';

/// The interface for an account allowance EVM hook.
interface IHieroAccountAllowanceEvmHook {
    /// Combines HBAR and HTS asset transfers.
    struct Transfers {
        /// The HBAR transfers
        IHederaTokenService.TransferList hbar;
        /// The HTS token transfers
        IHederaTokenService.TokenTransferList[] tokens;
    }

    /// Combines the full proposed transfers for a Hiero transaction,
    /// including both its direct transfers and the implied HIP-18
    /// custom fee transfers.
    struct ProposedTransfers {
        /// The transaction's direct transfers
        Transfers direct;
        /// The transaction's assessed custom fees
        Transfers customFee;
    }

    /// Decides if the proposed transfers are allowed, optionally in
    /// the presence of additional context encoded by the transaction
    /// payer in the extra args.
    /// @param installer The address of the installing account for which the hook is being executed
    /// @param proposedTransfers The proposed transfers
    /// @param args The extra arguments
    /// @return true If the proposed transfers are allowed, false or revert otherwise
    function allow(
       address installer,
       ProposedTransfers memory proposedTransfers,
       bytes memory args
    ) external payable returns (bool);
}
```

### Examples

Next we provide two examples of account allowance EVM hooks.

#### One-time passcode allowances

An NFT project prides itself on having only the very cleverest holders. They distribute their collection by daily
sending a NFT from the treasury to account `0.0.X`, and publishing a puzzle. The answer to the puzzle is a one-time
use passcode that allows the solver to collect the NFT.

In particular, the project team installs on account `0.0.X` at index `1` an account allowance lambda EVM hook that
references a contract created as below.

```solidity
import "./IHieroAccountAllowanceEvmHook.sol";

contract OneTimeCodeTransferAllowance is IHieroAccountAllowanceEvmHook {
    /// The hash of a one-time use passcode string, at storage slot 0x00
    bytes32 passcodeHash;

    /// Allow the proposed transfers if and only if the args are the
    /// ABI encoding of the current one-time use passcode in storage.
    ///
    /// NOTE: this lambda's behavior does not depend on the installer address,
    /// only the contents of the installed lambda's 0x00 storage slot
    function allow(
        address installer,
        IHieroTransferAllowance.ProposedTransfers memory,
        bytes memory args
    ) external override payable returns (bool) {
        require(address(this) == 0x16d, "Contract can only be called as a hook");
        (string memory passcode) = abi.decode(args, (string));
        bytes32 hash = keccak256(abi.encodePacked(passcode));
        bool matches = hash == passcodeHash;
        if (matches) {
            passcodeHash = 0;
        }
        return matches;
    }
}
```

As great aficionados of the project, we see one day that `0.0.X` holds our favorite NFT of all, serial `123`; and that a
`LambdaSStore` from `0.0.X` set the storage slot with key `0x00` to the hash
`0xc7eba0ccc01e89eb5c2f8e450b820ee9bb6af63e812f7ea12681cfdc454c4687`. We rush to solve the puzzle, and deduce the
passcode is the string, `"These violent delights have violent ends"`. Now we can transfer the NFT to our account `0.0.U`
by submitting a `CryptoTransfer` with,

```text
NftTransfer {
  senderAccountID: 0.0.X
  receiverAccountID: 0.0.U
  serialNumber: 123
  sender_allowance_hook_call: HookCall {
    index: 1
    evm_hook_call: EvmHookCall {
      evm_call_data: "These violent delights have violent ends"
    }
  }
}
```

Compare this example to the pure smart contract approach, where the project would need to write a more complex smart
contract that is aware of what serial number it currently holds; and makes calls to the HTS system contract to
distribute NFTs. Instead of the team using `LambdaSStore` to update the passcode with less overhead and cost to
the network than even a `ConsensusSubmitMessage`, they would need to submit a `ContractCall`. Instead of us using a
`CryptoTransfer` to collect our prize with maximum legibility and minimum cost, we would also need to submit a
`ContractCall` to the project's smart contract with a significantly higher gas limit.

For a trivial example like this, the cost and efficiency deltas may not seem decisive (unless the project was
running a very large number of these puzzles). But the idea of releasing contracts from the burden of duplicating
native protocol logic is deceptively powerful. The cost and efficiency savings for a complex dApp could be enormous,
unlocking entire new classes of applications that would be impractical to build on Hedera today.

#### Receiver signature waiver for HTS assets without custom fees

In this example we have our own account `0.0.Y` with `receiver_sig_required=true`, and want to carve out an exception
for exactly HTS token credits to our account with no assessed custom fees. We install a pure EVM hook at index `2`
whose referenced contract is as follows,

```solidity
import "./IHederaTokenService.sol";
import "./IHieroAccountAllowanceEvmHook.sol";

contract CreditSansCustomFeesTokenAllowance is IHieroAccountAllowanceEvmHook {
    /// Allows the proposed transfers only if,
    ///   (1) The only transfers are direct HTS asset transfers
    ///   (2) The installer is not debited
    ///   (3) The installer is credited
    function allow(
        address installer,
        IHieroTransferAllowance.ProposedTransfers memory proposedTransfers,
        bytes memory args
    ) external override view returns (bool) {
        require(address(this) == 0x16d, "Contract can only be called as a hook");
        if (proposedTransfers.direct.hbar.transfers.length > 0
                || proposedTransfers.customFee.hbar.transfers.length > 0
                || proposedTransfers.customFee.tokens.length > 0) {
            return false;
        }
        bool installerCredited = false;
        for (uint256 i = 0; i < proposedTransfers.tokens.length; i++) {
            IHederaTokenService.AccountAmount[] memory transfers = proposedTransfers.tokens[i].transfers;
            for (uint256 j = 0; j < transfers.length; j++) {
                if (transfers[j].accountID == installer) {
                    if (transfers[j].amount < 0) {
                        return false;
                    } else if (transfers[j].amount > 0) {
                        installerCredited = true;
                    }
                }
            }
            IHederaTokenService.NftTransfer[] memory nftTransfers = proposedTransfers.tokens[i].nftTransfers;
            for (uint256 j = 0; j < nftTransfers.length; j++) {
                if (nftTransfers[j].senderAccountID == installer) {
                    return false;
                } else if (nftTransfers[j].receiverAccountID == installer) {
                    installerCredited = true;
                }
            }
        }
        return installerCredited;
    }
}
```

## Backwards Compatibility

This HIP adds a net new feature to the protocol. Any account that does not install a hook will see
identical behavior in all circumstances.

## Security Implications

Because EVM hook executions are subject to the same `gas` charges and throttles as normal contract executions,
they do not introduce any new denial of service vector.

The main security concerns with account allowance hooks are the same as with smart contracts. That is,
- A hook author could code a bug allowing an attacker to exploit the hook.
- A malicious dApp could trick a user into installing a hook with a backdoor for the dApp author to exploit.

Hook authors must mitigate the risk of bugs by rigorous testing and code review. Users must remain vigilant about
signing transactions from dApps of questionable integrity.

## Reference Implementation

In progress, please see [here](https://github.com/hashgraph/hedera-services/pull/17551).

## Rejected Ideas

1. We considered **automatic** hooks that execute even without being explicitly referenced by a transaction.
   While this feature could be useful in the future, we deemed it out of scope for this HIP.
2. We considered adding `IHieroExecutionEnv` interface to the `0x16d` system contract with APIs available only
   to executing EVM hooks. While interesting, there was no obvious benefit for account allowances and the initial
   implementation.
3. We considered using a family of allowance extension points, one for each type of asset exchange. (That is,
   `PRE_HBAR_DEBIT`, `PRE_FUNGIBLE_CREDIT`, `PRE_NFT_TRANSFER`, and so on.) Ultimately the single `ACCOUNT_ALLOWANCE`
   extension point seemed more approachable, especially as calls can encode any extra context the hook's `allow()`
   method needs to efficiently focus on one aspect of the proposed transfers.

## Open Issues

No known open issues.

## References

- [HIP-18: Custom Hedera Token Service Fees](https://hips.hedera.com/hip/hip-18)
- [HIP-376: Support Approve/Allowance/transferFrom standard calls from ERC20 and ERC721](https://hips.hedera.com/hip/hip-376)
- [HIP-904: Frictionless Airdrops](https://hips.hedera.com/hip/hip-904)
- [HIP-991: Permissionless revenue-generating Topic Ids for Topic Operators](https://hips.hedera.com/hip/hip-991)

## Copyright/license

This document is licensed under the Apache License, Version 2.0 -- see [LICENSE](../LICENSE) or (https://www.apache.org/licenses/LICENSE-2.0)
