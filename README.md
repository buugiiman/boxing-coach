# Real-Time Mobile Boxing Coach

An Android application that analyzes shadowboxing sessions using on-device pose estimation and LLM-based coaching feedback.

## How It Works

1. Select your stance (Orthodox / Southpaw) and session duration (10s / 15s / 30s)
2. Press START and shadowbox in front of your phone
3. After the session, MoveNet Lightning keypoints are sent to Claude Haiku API
4. Results screen shows punch counts, coach feedback, strengths, improvements, and guard analysis

**Pipeline:** CameraX → MoveNet Lightning (on-device TFLite) → Feature Extraction → Claude Haiku API → Results

## Repository Structure

```
boxing-coach/
├── app/               Android Studio project (Kotlin)
├── ppt/               Presentation slides (BoxingCoach_Presentation.pptx)
├── apk/               Installable APK (BoxingCoach.apk)
└── README.md
```

## Installation

### Run from APK
Download `apk/BoxingCoach.apk` and install directly on an Android 8.0+ device.
Enable **Install from unknown sources** in your device settings if prompted.

### Build from Source
1. Clone the repo
2. Open the `app/` folder in Android Studio
3. Create `app/local.properties` and add:
   ```
   ANTHROPIC_API_KEY=your_api_key_here
   ```
4. Get an API key at [console.anthropic.com](https://console.anthropic.com)
5. Build and run on an Android device (API 26+)

## Tech Stack

- **Kotlin** — Android app
- **CameraX** — Camera access and frame streaming
- **MoveNet Lightning** — On-device pose estimation (TFLite INT8)
- **Claude Haiku API** — Session analysis and coaching feedback
- **Android 8.0+** (API 26)

## Team

- Butemj Bat-Orshikh (2021-18806)
- Assem Sagyndykova (2026-81668)

Seoul National University — Mobile Computing 2026