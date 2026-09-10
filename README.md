# DriveSCO

Material 3 Android app for testing music playback over Bluetooth HFP/SCO when a car stereo does not provide A2DP music support.

## Current prototype

- Material 3 UI
- Paired Bluetooth device selection
- Android 10+ MediaProjection audio playback capture
- HFP/SCO communication routing
- 16 kHz mono PCM bridge
- Foreground media-projection service
- GitHub Actions debug APK build

## Important

The prototype does not bypass the Bluetooth HFP codec negotiated by Android and the vehicle. It is intended to establish a clean, controllable baseline before experimenting with codec detection and audio processing.

## Build

GitHub Actions builds `app-debug.apk` and publishes it as the `DriveSCO-debug` artifact.
