# VlogCam

A personal Android camera app for photos, videos, and **dual-camera / vlog recording**:
the front and rear cameras record at the same time into a single video.

## Features

**Dual / Vlog mode**
- Both cameras recorded at once into one MP4, with sound
- Layouts: **Picture-in-picture**, **Side by side**, **Top / bottom**, each camera cropped to fill its area
- Full-width 9:16 frame in portrait, 16:9 in landscape
- Picture-in-picture: move the inset to any corner, 3 sizes, swap which camera is the main one
- Change layout, move the inset or swap cameras live, even while recording
- Zoom each camera separately (the "Zoom: Rear/Front" button picks which one)
- Stabilization (when the phone supports it), 720p / 1080p
- Dual snapshot button for still photos of the combined view (also works while recording)
- The preview shows exactly what gets recorded

**Photo & Video modes**
- Pinch to zoom, zoom slider and quick zoom buttons (0.5x ultra-wide if your phone has one, 1x, 2x, 5x…)
- Tap to focus and meter, double-tap to switch cameras
- Flash (off/auto/on) for photos, torch for video
- Video and preview stabilization
- 4K / 1080p / 720p video (whatever your phone supports), pause and resume recording
- Night / HDR / Portrait / Retouch photo modes when the phone's manufacturer provides them
- Exposure compensation, rule-of-thirds grid, 3s / 10s self-timer, mirrored selfies
- Volume buttons act as the shutter
- Works in portrait and landscape and follows the phone's auto-rotate setting

**Export for social media**
- Tap the **⇪** button next to the gallery thumbnail (or long-press the thumbnail)
- Pick the last capture or any photo/video from your gallery
- Choose a size: Instagram Reel/Story (9:16), Post 4:5 or 1:1; YouTube Video (16:9) or Shorts (9:16);
  Facebook Reel/Story (9:16), Feed 4:5 or landscape 16:9
- **Crop to fill** (no bars) or **Fit** (whole picture, black bars)
- The result is saved to `Movies/VlogCam/Exports` or `Pictures/VlogCam/Exports`, with a **Share** button to post it straight away

Photos are saved to `Pictures/VlogCam`, videos to `Movies/VlogCam`, so they show up in your gallery.

## Install on your phone

1. Open the repository's **Releases** page on your phone and find **VlogCam – latest build**.
2. Download `VlogCam.apk` and open it.
3. If Android asks, allow your browser or file manager to install unknown apps.

Each push to the repository rebuilds the APK automatically (see the **Actions** tab).

### Updates inside the app

When a newer build is published, a yellow **"New version available · Tap to update"** banner
appears on the camera screen. Tap it to download the update and confirm Android's install prompt.
The first time, Android asks you to allow VlogCam to install apps. The app checks when it opens
and again every 10 minutes while it's open.

## Requirements

- Android 8.0 or newer for photo and video.
- Dual mode needs **Android 11 or newer** and a phone whose maker lets apps use two cameras at once.
  Most recent Pixel and Samsung Galaxy phones support it, and many mid-range phones do too. On an unsupported phone the app says so and switches to normal video.

## Building yourself

You need JDK 17 and the Android SDK, then run:

```
./gradlew assembleRelease
```

Built with Kotlin and CameraX (Dual concurrent camera with composition).

Note: the signing keystore (`app/vlogcam.keystore`) is committed on purpose so every build can update the installed app. It's fine for a personal sideloaded app, but don't use it to publish to the Play Store.
