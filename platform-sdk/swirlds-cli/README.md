# Platform CLI

A command-line interface toolkit for the Platform, providing various utilities.
// TBD - https://github.com/hiero-ledger/hiero-consensus-node/issues/20915

## Supported Commands

### Updating State with a Block Stream

The `block-stream apply` command uses a set of block files to advance a given state from the current state to the target state.

**Usage:**

```bash
pcli block-stream apply --id=<node id> --main-name=com.hedera.node.app.ServicesMain -L "data/lib" -L "data/apps" \
"<path to original state>" \
"<path to a directory with block stream files>" \
[-o="<path to output directory>"] [-h="<hash of the target state>"] [-t="<target round>"]
```

Notes:

- The command checks if the block stream contains the next round relative to the initial round to ensure continuity. It fails if the next round is not found.
- If a target round is specified, the command will not apply rounds beyond it, even if additional block files exist.
  The command also verifies that the corresponding blocks are present. It will fail if a block is missing or if the final round in the stream does not match the target round.
- The command can validate the hash of the resulting state against a provided hash (see the `-h `parameter).
- If the `-o` parameter is specified, the command uses the provided path as the output directory for the resulting snapshot. If not specified, the default output directory is `./out`.
