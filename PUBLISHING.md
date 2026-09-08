# Publishing & wallet integration

The kit publishes via [JitPack](https://jitpack.io), like every other HorizontalSystems
`*-kit-android` library. `jitpack.yml` (`jdk: openjdk17`) and the `maven-publish` block in
`xrpkit/build.gradle` mirror `stellar-kit-android`.

JitPack builds the multi-module repo, skips the `app` module, and publishes the library under
the repo-name coordinate:

```
com.github.horizontalsystems:xrp-android:<commit-or-tag>
```

## Publish steps

1. Push to `horizontalsystems/xrp-android`.
2. Open `https://jitpack.io/#horizontalsystems/xrp-android`, look up the commit, or let
   the wallet's first dependency resolution trigger the build.

## Local development

```
./gradlew :xrpkit:publishToMavenLocal
```

publishes `com.github.horizontalsystems:xrp-android:local`. The wallet's
`settings.gradle.kts` already includes `mavenLocal()`, so set `xrpKit = "local"` in its
version catalog to consume an unpublished build.

## Wallet-side wiring (`unstoppable-wallet-android`)

`gradle/libs.versions.toml`, under the wallet kits:

```toml
xrpKit = "<commit-hash>"
```

and in the kit module list:

```toml
kit-ripple = { module = "com.github.horizontalsystems:xrp-android", version.ref = "xrpKit" }
```

`walletkit-chain-ripple/build.gradle.kts`:

```kotlin
api(libs.kit.ripple)
```
