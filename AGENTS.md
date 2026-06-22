This project is an Android app that plays radios.

## Technical

The main language is Kotlin.

The architecture is:
- a webview for displaying and selecting radios
- an audio player using ExoPlayer in a background service (MediaPlaybackService.kt)
- The webview communicate / sync with the app using a WebAppInterface (WebAppInterface.kt)
- The playback service also communicate with the main activity using Intent and an EventBus