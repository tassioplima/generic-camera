# RawCam

A basic Android camera app built directly on Camera2 (no CameraX), focused on capturing
straight-off-the-sensor photos and video with no beautification filters or AI post-processing,
plus quick sharing to WhatsApp, Telegram and Instagram.

This build is tuned and tested specifically for the **Xiaomi 17T**, including its exact
multi-lens zoom breakpoints (ultra-wide / main / telephoto) and its 120fps/240fps high-speed
video modes. It may work on other Android 15/16 devices, but resolution, fps and zoom options
are all read live from each device's own Camera2 characteristics rather than hardcoded, so
capability differences on other phones are expected.

## Features

- JPEG + optional RAW (.dng) capture, with noise reduction/edge enhancement disabled
- Photo aspect ratio (4:3 / 16:9), composition grid
- Video at 1080p/4K, including 120fps/240fps high-speed modes where the hardware supports them
- Zoom presets computed from each physical lens's real focal length + sensor size, marking
  which ratios are true optical lens switches vs. digital crop
- Tap-to-focus with live AF-state feedback, long-press to lock focus/exposure with a
  drag-to-adjust exposure gesture
- Bottom-right capture bubble with a swipeable review gallery and one-tap share to
  WhatsApp/Telegram/Instagram
- Settings (aspect ratio, grid, RAW, shutter sound/flash) persist across restarts

## Building

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK platform 36 and build-tools 36.1.0.

## Releases

Tagged pushes (`vX.Y.Z`) trigger a GitHub Actions workflow that builds the debug APK and
publishes it as a GitHub Release asset.
