# Publishing & wallet integration

The kit publishes via [JitPack](https://jitpack.io), like every other HorizontalSystems
`*-kit-android` library. `jitpack.yml` (`jdk: openjdk17`) and the `maven-publish` block in
`ripplekit/build.gradle` mirror `stellar-kit-android`.

JitPack builds the multi-module repo, skips the `app` module, and publishes the library under
the repo-name coordinate:

```
com.github.horizontalsystems:ripple-chain-android:<commit-or-tag>
```

## Publish steps

1. Push to `horizontalsystems/ripple-chain-android`.
2. Open `https://jitpack.io/#horizontalsystems/ripple-chain-android`, look up the commit, or let
   the wallet's first dependency resolution trigger the build.

## Local development

```
./gradlew :ripplekit:publishToMavenLocal
```

publishes `com.github.horizontalsystems:ripple-chain-android:local`. The wallet's
`settings.gradle.kts` already includes `mavenLocal()`, so set `rippleKit = "local"` in its
version catalog to consume an unpublished build.

## Wallet-side wiring (`unstoppable-wallet-android`)

`gradle/libs.versions.toml`, under the wallet kits:

```toml
rippleKit = "<commit-hash>"
```

and in the kit module list:

```toml
kit-ripple = { module = "com.github.horizontalsystems:ripple-chain-android", version.ref = "rippleKit" }
```

`walletkit-chain-ripple/build.gradle.kts`:

```kotlin
api(libs.kit.ripple)
```
