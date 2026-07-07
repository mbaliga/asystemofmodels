# Developing asom on the Steam Deck

The dev host is a Steam Deck on SteamOS. SteamOS's root filesystem is
read-only, so all development happens inside a **distrobox** (podman) Ubuntu
box. The guaranteed build path is **GitHub Actions** — nothing below is
required beyond JDK + git; a local Android SDK is optional.

## 1. Distrobox setup (one-time)

```sh
# On the Deck (Desktop Mode, Konsole):
distrobox create --name asom-dev --image ubuntu:24.04
distrobox enter asom-dev

# Inside the box:
sudo apt update
sudo apt install -y openjdk-17-jdk git curl unzip gh
```

Authenticate GitHub CLI if you want to push / watch CI from the box:

```sh
gh auth login
```

## 2. Clone and build (pure JVM — no Android SDK needed)

```sh
git clone https://github.com/asystemofcells/asystemofmodels.git
cd asystemofmodels
./gradlew jvmTest        # :core:* and :server tests on the bare JDK
./gradlew :server:run    # desktop server on 127.0.0.1:11435 (from P3)
```

This is the everyday loop. The Android side of the build is validated by CI
on every push (`.github/workflows/ci.yml` assembles a debug APK artifact).

## 3. Optional: local Android SDK

Only needed if you want to build the APK locally instead of grabbing the CI
artifact.

```sh
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest

export ANDROID_HOME=~/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

With `ANDROID_HOME` set (or a `local.properties` containing `sdk.dir=...`),
`settings.gradle.kts` automatically includes the Android modules and
`./gradlew :app:assembleDebug` works.

## 4. Sideloading to the RedMagic

From the distrobox (`platform-tools` provides `adb`), with USB debugging on:

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or download the `asom-debug-apk` artifact from the GitHub Actions run and
install via Termux / file manager on the device.
