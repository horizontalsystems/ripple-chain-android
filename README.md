# ripple-chain-android

XRP Ledger kit for Android, in the style of the other HorizontalSystems `*-kit-android`
libraries. It derives keys, syncs balance, reserve, trust lines and history, and signs and
submits transactions without any third-party XRPL SDK.

## What it does

- **Keys.** secp256k1 at `m/44'/144'/0'/0/0` from a BIP39 seed, the path Ledger, Trust Wallet,
  Exodus and Xaman use, so the same words give the same `r...` address everywhere.
- **Addresses.** Classic `r...` addresses and X-addresses (`X...` mainnet, `T...` testnet), which
  pack a destination tag.
- **Sync.** Polls `server_state`, `account_info`, `account_lines` and `account_tx` over JSON-RPC
  with endpoint failover. History is paged with `marker`; received amounts come from
  `meta.delivered_amount`.
- **Reserve.** `minimumBalance` = base reserve + owner reserve × owned objects, from the network,
  never hardcoded. `availableBalance` is what can actually be sent.
- **Sending.** `Payment` (XRP and issued tokens, with destination tag and memo) and `TrustSet`.
  Transactions are serialized with a built-in canonical binary codec, signed with RFC 6979
  low-S ECDSA over SHA-512Half, and submitted as `tx_blob`. Every send carries
  `LastLedgerSequence`; a submission that is not validated by then is marked failed.

## Usage

```kotlin
val seed = Mnemonic().toSeed(words)
val kit = RippleKit.getInstance(context, RippleWallet.Seed(seed), Network.MainNet, walletId = "wallet-1")
kit.start()

kit.balanceFlow.collect { xrp -> /* Amount.Xrp */ }
kit.minimumBalanceFlow.collect { reserve -> /* locked XRP */ }
kit.transactionsFlow.collect { changed -> /* new or updated Transaction records */ }

val fee = kit.estimateFee()
if (kit.requiresDestinationTag(to)) { /* ask for a tag */ }
if (!kit.doesAccountExist(to)) { /* amount must be at least kit.baseReserve */ }
val tx = kit.sendXrp(to, BigDecimal("1.5"), destinationTag = 12345)
```

Watch-only: `RippleWallet.WatchOnly("r...")`. Sends then throw `RippleKit.WalletError.WatchOnly`.

Tokens: `kit.trustLinesFlow`, `kit.getTokenBalanceFlow(currency, issuer)`,
`kit.setTrustLine(currency, issuer)` (costs one owner reserve while the line exists),
`kit.sendToken(currency, issuer, to, amount)`. Currency codes are the ledger form (3 characters
or 40 hex); `RippleKit.displayCurrencyCode` turns `524C5553440000…` into `RLUSD`.

## Layout

| Package | Contents |
|---|---|
| `crypto` | Ripple base58, SHA-512Half, AccountID, X-address |
| `codec` | Field table, currency and amount encoding, canonical STObject serializer |
| `transaction` | `Signer`, `TransactionBuilder`, `TransactionSender` |
| `network` | JSON-RPC provider with failover, response models, connectivity |
| `sync` | Timer, account/ledger syncer, `account_tx` syncer, converter |
| `database` | Room storage, one database per wallet id |

Tests run against the xrpl.js binary-codec fixtures (`codec-fixtures.json`,
`data-driven-tests.json`) and the ripple-keypairs signing vector:

```
./gradlew :ripplekit:testDebugUnitTest
```

The `app` module is a sample: restore a mnemonic or watch an address, fund a testnet account
from the faucet, send XRP with a tag, and see history.

## Not yet covered

Family-seed (`s...`) import, ed25519 keys, WebSocket subscriptions, AccountDelete, MPTs, NFTs.
