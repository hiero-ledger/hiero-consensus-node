## Concept of plan for indirect keys

A stream-of-consciousness survey of the work needed to implement indirect keys.

The todos inside each heading are not perfectly decoupled, but there should not be major dependencies between them and
many could be done in parallel.

### Extend the key infrastructure

Update the HAPI protobufs and state protobufs and state definitions to represent indirect
keys so that,
1. Users (including our test clients) can request indirect keys; and,
2. The node software can _capture_ those requests in state.

**Todo:**
- [ ] Add an `IndirectKey` message w/ field `target_id` (to start, one of `AccountID`, `ContractID`)
- [ ] Extend `Key` message with an `IndirectKey` choice
- [ ] Extend state `Account` message with five new fields,
    1. `Key materialized_key` - If this account's key uses indirection, its last fully materialized version with
    all indirections "flattened".
    2. `uint32 num_indirect_key_users` - How many accounts are indirect users of this account's key (needed both for
    rent and enforcing limits on max number of indirect users).
    3. `uint32 max_remaining_propagations`  - An upper bound on how many indirect users still need a propagated version
    of this account's key since its last change.
    4. `AccountID first_key_user_id` - If set, the head of the doubly linked list of indirect user ids for this account.
    5. `AccountID next_in_line_key_user_id` - If set, the id of the indirect user to serve next for an in-progress key
    change propagation.
- [ ] Add a `TokenService.IndirectKeyUsers` K/V state with key `(AccountID keyAccountId, AccountID indirectUserId)`
and value `(AccountID prevUserId, AccountID nextUserId)`.

### Add task queue infrastructure

Further update the state protobufs and state definitions for a new `SystemTaskService` under `hedera-app` to include a
FIFO task queue with extensible task type. Give an initial task type that means "propagate an account's key to
its indirect user next in line".

**Todo:**
- [ ] Add `KeyPropagation` message with `AccountID key_account_id` field to track work to be done for an in-progress
propagation of the referenced account's key.
- [ ] Add `SystemTask` message with `oneof task { KeyPropagation key_propagation = 1 }`;
- [ ] Add new `SystemTaskService` under with initial `V069Schema` that defines a single `SystemTask` queue state
whose elements are of type `SystemTask`.

## Implement and expose the `SystemTasks` API to `TransactionHandler`s

Define the `SystemTasks` service that will be responsible for registering and distributing system tasks, providing a
nice API boundary to its clients in rest of the system---primarily the `HandleWorkflow`, `TransactionHandler`s, and
`SystemTaskHandler`s.

**Todo:**
- [ ] Define the `SystemTasks` interface with minimal `peek`/`poll`/`offer` API.
- [ ] Implement `SystemTasks` as approximately a `SystemTaskService` writable store by `WritableQueueState<SystemTask>`.
- [ ] Extend `HandleContext` with access to at least `SystemTasks.offer()` so transaction handlers can enqueue tasks
that need to be done to finish their business logic, but have technically unbounded work and cannot be done
"synchronously" in the same handle call.
- [ ] Define a `SystemTaskContext` analogous to the `SystemContext` but with a getter for the just-popped `SystemTask`
plus at least `SystemTasks.offer()` again so task can keep its logical work in motion when needed.
- [ ] Define an SPI `SystemTaskHandler` that service modules can expose implementations of; add centralized
`SystemTaskDispatcher` singleton a la the `TransactionDispatcher` to map a `SystemTask` to its handler.

## Hook the `SystemTasks` into the `HandleWorkflow`

Update `HandleWorkflow` logic to consider not just pending scheduled transactions, but also pending system tasks as it
has both opportunity and capacity to dispatch them.

**Todo:**
- [ ] Update `HandleWorkflow.executeAsManyScheduled()` to return the work capacity remaining after dispatching scheduled
transactions (in particular, the remaining usable consensus times left in the current platform transaction's "window").
- [ ] Implement a concrete `SystemTaskContext` as anonymous class a la `SystemTransactions.newSystemContext()`.
- [ ] Add a `HandleWorkflow.doAsManyPendingTasks()` method call that takes capacity left after scheduled transactions and
polls/dispatches `SystemTask`s as capacity allows.

## Update `PreHandleContext` for indirect keys

During pre-handle, if we have a signing requirement from an account that indirection (i.e., has non-null
`materialized_key` field), we should always require **that** key to have signed when checking signing requirements, and
not the its "template key" that specifies the indirection.

**Todo:**
- [ ] Update all `requireKeyOrThrow()` variants that take an `AccountID` to prioritize a fetched account's materialized key.

## Update `CryptoUpdateHandler` for indirect keys

Add business logic that runs when an otherwise valid update encounters key indirection.

**Todo:**
- [ ] Before updating `0.0.A`'s template key, if its previous template uses indirection, compute the set of account ids
that `0.0.A` is _no longer_ an indirect user of (call this `old`) and the set of account ids it _newly_ an an indirect
user of (call this `new`).
  * For each account id `0.0.X` in `old`, decrement its `num_indirect_key_users` and remove `0.0.A` from `0.0.X`'s
  doubly-linked list of indirect users.
- [ ] For each account id `0.0.X` in `new`, apply this logic to update `0.0.X` when fetching its key to use in
materializing `0.0.A`'s template:
  * Increment `num_indirect_key_users` for `0.0.X`.
  * If `0.0.X` has an in-progress propagation (non-null `next_in_line_key_user_id`), then:
    - Use the doubly-linked list pointers to insert `0.0.A` in the `0.0.X` indirect users list **behind** the
      `next_in_line_key_user_id`.
    - Leave `max_remaining_propagations` untouched.
  * Otherwise, if `0.0.X` has no in-progress propagation (null `next_in_line_key_user_id`), then:
    - Insert `0.0.A` before `first_key_user_id` and make `first_key_user_id = 0.0.A`.
- [ ] Materialize `0.0.A`s from its template; update both `0.0.A`'s template and materialized keys.
- [ ] If `0.0.A`'s (materialized) key changed and it has non-zero `num_indirect_key_users`, then:
  * Reset its `max_remaining_propagations = num_indirect_key_users`.
  * Set `next_in_line_key_user_id = first_key_user_id`.
  * Use `handleContext.offerTask()` to schedule a `SystemTask` that will kick off the work of propagating its
  (materialized) key to its indirect users.

## Implement the `SystemTaskHandler` for `KeyPropagation` tasks in `TokenService`

Add business logic that runs when there is capacity to keep propagating a key to its indirect users.

**Todo:**
- [ ] Given a `KeyPropagation` task, fetch the referenced `0.0.A`.
- [ ] If `0.0.A` exists and is not deleted,
  * Look at `next_in_line_key_user_id` and load the referenced account `0.0.U`.
  * If `0.0.U` exists and is not deleted,
    - Traverse its `key` template and materialized keys in parallel, re-materializing every reference to `0.0.A`.
    - (I.e., if `0.0.U`'s template has multiple indirections, save the work of doing those for later.)
  * Replace `0.0.U`'s materialized key via a `CryptoUpdate` that **uses `0.0.U` as payer**.
    - If this fails due to `INSUFFICIENT_PAYER_BALANCE`, rollback.
    * Otherwise, if the `0.0.U` has non-zero `num_indirect_key_users`, do as in `CryptoUpdateHandler` above:
      - Reset its `max_remaining_propagations = num_indirect_key_users`.
      - Set `next_in_line_key_user_id = first_key_user_id`.
      - Use `systemTaskContext.offerTask()` to enqueue SystemTask to propagate the loaded account's key
- [ ] Decrement `max_remaining_propagations` for `0.0.A`
- [ ] If `max_remaining_propagations = 0`, null out `next_in_line_key_user_id`; otherwise,
  * Set `next_in_line_key_user_id = COALESCE(next_in_line_key_user_id.next, first_key_user_id)`
  * Use `systemTaskContext.offerTask()` to keep propagating `0.0.A`'s key rotation.

## Update `CryptoDelete` handler for indirect keys

Add business logic that runs when an account is deleted, to remove its footprint on the (constrained)
set of accounts it uses indirect references to.

**Todo:**
- [ ] Traverse the deleted account `0.0.A`'s template key for any references to e.g. `0.0.X`; for each,
  * If `0.0.X` still exists and is not deleted,
    - If its `next_in_line_key_user_id` is `0.0.A`,
      * Set `next_in_line_key_user_id = COALESCE(next_in_line_key_user_id.next, first_key_user_id)`
    - Use `0.0.X`'s doubly-linked list of users at key `(0.0.X, 0.0.A)` to remove `0.0.A` from `0.0.X`'s indirect users.
- [ ] Decrement `num_indirect_key_users` but _not_ `max_remaining_propagations` in case `0.0.A` was before
  `next_in_line_key_user_id` in the doubly-linked list.
- [ ] Null out all indirect key fields before setting `0.0.A`'s deleted field to true and returning.

## Add configuration

Provide dynamic system properties to control the impact of indirect keys on the network.

**Todo:**
- [ ] Add and enforce `accounts.maxIndirectKeyUsers=10`
- [ ] Add and enforce `accounts.maxIndirectKeyRefs=100`
- [ ] Add and enforce `scheduling.minReservedSystemTaskNanos=10`
- [ ] Add and enforce `scheduling.maxSystemTasksPerUserTxn=10`

## Update fees

Ensure network charges for all additional work done to store and manage indirect keys. (E.g., $10 USD per indirect key).

**Todo:**
- [ ] Increase cost of `CryptoUpdate` with each indirect key "extra" added by a configurable amount of
`fees.indirectKeyExtraTinycents=100_000_000_000`.