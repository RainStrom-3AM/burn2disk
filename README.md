# Burn2Disk

An Android app that burns bootable ISO/IMG images to USB OTG drives **without root**.
Built with Jetpack Compose + Material 3, MVVM + Repository, and Hilt.

- Package: `com.burnto.disk`
- Min SDK 21 · Target/Compile SDK 34 · Kotlin
- Dark-only theme · amber (`#E8A020`) on near-black (`#0D0D0D`)

## How it works

| Stage | Component | Notes |
|-------|-----------|-------|
| Discover USB | `UsbDeviceManager` | libaums `getMassStorageDevices` + runtime permission |
| Raw block access | `RawUsbBlockDevice` | builds an LBA-addressed SCSI block device via libaums' public factories |
| Format | `Fat32Formatter` | writes MBR + FAT32 (BPB, 2 FATs, FSInfo, root dir) at block level — libaums has no `mkfs` |
| Parse ISO | `IsoParser` | dependency-free ISO 9660 **+ Joliet** over `RandomAccessFile` |
| Detect OS | `IsoDetector` | Windows / Ubuntu-Debian / Fedora-RHEL / Arch + UEFI/BIOS + arch |
| Copy | `BurnEngine` | 64 KiB chunks via libaums `UsbFile`, progress every 500 ms |
| WIM split | `WimSplitter` | bundled `wimlib-imagex` ARM64 binary via `ProcessBuilder` |
| Download | `DownloadManager` | OkHttp streaming, Range resume, post-download SHA-256 |
| Background | `BurnService` / `DownloadService` | foreground services + wake lock + progress notification |

Screens: Home → ISO Source → ISO Info → Device Selection → Burn Progress → Result.

## Required manual step: the wimlib binary

Windows ISOs whose `sources/install.wim` exceeds the 4 GB FAT32 file limit are split
into `install.swm` parts. A `.swm` set is **not** a byte-split of the WIM, so we shell
out to a real `wimlib-imagex`.

Place a statically-linked ARM64 build at:

```
app/src/main/assets/bin/wimlib-imagex
```

See `app/src/main/assets/bin/README.txt`. Until that binary is supplied, non-Windows
ISOs and Windows ISOs with a sub-4 GB WIM burn fine; oversized-WIM burns fail with a
clear message. **wimlib is GPLv3** — bundling it makes the app a combined work that must
comply with the GPLv3.

## Build

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Verified building against
Android SDK 34 / build-tools 34.0.0 with JDK for AGP 8.5.

## Important caveats

- **Real hardware required to validate the burn.** USB mass-storage I/O, the FAT32
  formatter, and `wimlib-imagex` execution can't be exercised on an emulator or CI.
  The code compiles and packages; on-device verification of an actual bootable result
  is the next step.
- **OEM auto-mount.** Some Android builds (notably Samsung One UI) auto-mount OTG
  storage and may need that disabled to let the app claim the device.
- **FAT32 formatter** writes a single MBR partition (type 0x0C) 1 MiB-aligned. It is a
  from-scratch implementation; treat the first on-device runs as validation.
- `android.permission.USB_PERMISSION` from the original spec is not a real system
  permission, so permission is obtained at runtime via `UsbManager.requestPermission`.
