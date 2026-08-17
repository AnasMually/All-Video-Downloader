# Third-party notices

All Video Downloader links against third-party open-source components. Each component remains subject to its upstream license and notices.

- AndroidX, Jetpack Compose, Google Media3, Coil, Kotlin, and Gradle — retain their respective upstream licenses.
- App-local vector icons are derived from Google Material Icons, licensed under Apache License 2.0.

The Android application no longer packages Python, yt-dlp, QuickJS, FFmpeg, aria2, or youtubedl-android. Video extraction is performed by the separately deployed VideoFlow API. The app receives metadata and temporary source-media URLs from that API, then downloads the selected media directly on the device. Media3/MediaCodec and Android MediaMuxer are used only when client-side audio conversion or video/audio muxing is required.

This notice is informational and does not replace the license files distributed by upstream projects. A production release should include the exact notices corresponding to the resolved dependency versions.
