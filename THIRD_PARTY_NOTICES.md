# Third-party notices

All Video Downloader includes or links against third-party open-source components. Each component remains subject to its upstream license and notices.

- [youtubedl-android](https://github.com/yausername/youtubedl-android) — GPL-3.0; Android wrapper and packaged runtime integration.
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) — refer to the upstream repository for its current license and notices.
- [QuickJS](https://bellard.org/quickjs/) and the packaged Python runtime — retain their respective upstream licenses.
- AndroidX, Jetpack Compose, Google Media3 Transformer, Coil, Kotlin, and Gradle — retain their respective upstream licenses.

FFmpeg and aria2 are intentionally not packaged by this application. Audio conversion uses Media3/MediaCodec, and MP4 track muxing uses Android MediaMuxer.

This notice is informational and does not replace the license files distributed by upstream projects. A production release should include the exact notices corresponding to the resolved dependency and native-binary versions.
