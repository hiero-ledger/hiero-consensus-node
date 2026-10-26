# Legacy TSS state fixture

`pre-nova-public-state.json` contains public state extracted from
`hedera-node/test-clients/tss-startup-assets/ledgerA-genesis-network.json.gz`
at CN commit `251b8b97c12109a58ea50e3427f6a21076af9fb8`.
It retains the original CRS, hinTS construction, ledger ID, and compressed history proof.
Node private keys, transport metadata, obsolete proving-key metadata, and the approximately
31 MB uncompressed WRAPS proof are omitted. The tests exercise upgrade rejection, not old-proof verification.
