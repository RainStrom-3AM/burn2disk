Place the precompiled ARM64 (aarch64) `wimlib-imagex` binary here as:

    app/src/main/assets/bin/wimlib-imagex

Requirements:
- Statically linked (no dynamic libc/libcrypto deps that won't resolve on Android).
- Built for arm64-v8a. For 32-bit device support, also ship an armeabi-v7a build
  and select at runtime based on Build.SUPPORTED_ABIS.
- License: wimlib is GPLv3 (with some files LGPL). Bundling it makes Burn2Disk a
  combined work that must comply with the GPLv3 (ship the corresponding source
  / written offer, keep license notices). Review before shipping to Play Store.

At runtime WimSplitter copies this file to filesDir/bin/wimlib-imagex, runs
chmod 700, and invokes it via ProcessBuilder. See WimSplitter.kt.

The build is configured (android.androidResources.noCompress += "wimlib-imagex")
so the asset is stored uncompressed and can be streamed out verbatim.
