# Triggered Whisper - Android Controller App

A voice input app for Android that uses a Bluetooth controller (L1 button) to trigger speech-to-text. Supports both Gboard's native voice typing and network-based transcription backends (OpenAI, Whisper ASR, NVIDIA NIM).

## Features

- **L1 button voice input**: Press L1 on your Bluetooth controller to start/stop voice recording
- **Dual backend support**:
  - **Gboard Voice Typing**: Uses Android's native voice recognition (works in most apps)
  - **Network backends**: OpenAI API, Whisper ASR Webservice, NVIDIA NIM (works in terminal apps like Termius)
- **Auto IME switching**: Automatically switches keyboards when needed for terminal apps
- **Controller navigation**: R1+D-pad for Home/Back/Recent Apps

## Architecture

### Services

1. **NavigationAccessibilityService** - Main accessibility service that:
   - Intercepts controller button presses (L1, R1, D-pad)
   - Triggers voice input based on configured backend
   - Handles IME switching for network backends
   - Injects transcribed text into apps

2. **WhisperInputService** - Input Method Service (keyboard) that:
   - Provides InputConnection for text injection in terminal apps
   - Handles controller buttons when active as keyboard
   - Supports tmux key sequences (Ctrl+Q combinations)

3. **WhisperTranscriber** - HTTP client for network-based transcription:
   - Supports OpenAI API (gpt-4o-transcribe model)
   - Supports Whisper ASR Webservice
   - Supports NVIDIA NIM

4. **RecorderManager** - Audio recording manager:
   - Uses MediaRecorder for audio capture
   - Supports M4A (for OpenAI) and OGG (for NVIDIA NIM) formats

## Voice Input Flow

### For Regular Apps (Gboard Backend)

```
L1 Press → Find Gboard mic button → Click via accessibility → Gboard handles voice input
```

1. User presses L1 on controller
2. AccessibilityService searches for Gboard's "Use voice typing" button in the INPUT_METHOD window
3. Clicks the button via `AccessibilityNodeInfo.ACTION_CLICK`
4. Gboard's native voice recognition handles the rest

### For Terminal Apps (Network Backends)

```
L1 Press → Switch to WhisperInputService → Record audio → Transcribe via API →
Inject text via InputConnection → Switch back to previous keyboard
```

1. User presses L1 on controller
2. AccessibilityService saves current IME (e.g., Gboard)
3. Switches to WhisperInputService using `SoftKeyboardController.switchToInputMethod()`
4. Starts audio recording with RecorderManager
5. User presses L1 again to stop
6. Audio sent to configured API (OpenAI, etc.)
7. Transcribed text committed via `WhisperInputService.commitTextFromExternal()`
8. Automatically switches back to previous keyboard

## IME Switching (Android 11+)

The app uses `AccessibilityService.SoftKeyboardController.switchToInputMethod(String imeId)` to programmatically switch keyboards. This API is available in Android 11+ (API 30+) and doesn't require special permissions like `WRITE_SECURE_SETTINGS`.

```kotlin
// Switch to our IME
val controller = softKeyboardController
controller.switchToInputMethod(OUR_IME_ID)

// Later, switch back
controller.switchToInputMethod(previousImeId)
```

This enables seamless voice input in terminal apps that don't support standard accessibility paste actions.

## Text Injection Strategies

The app tries multiple strategies to inject transcribed text:

1. **ACTION_PASTE** - Works for standard EditText views
2. **ACTION_SET_TEXT** - Works for some custom editable views
3. **Custom accessibility actions** - Some apps define their own paste actions
4. **InputMethodService commit** - For terminal apps, uses `InputConnection.commitText()`
5. **Clipboard fallback** - Copies to clipboard if all else fails

## Settings

| Setting | Description |
|---------|-------------|
| Speech-to-Text Backend | Gboard Voice Typing, OpenAI API, Whisper ASR, NVIDIA NIM |
| Endpoint | API endpoint URL (for network backends) |
| API Key | Authentication key (for OpenAI) |
| Auto Switch Back | Return to previous keyboard after voice input |
| Floating Keyboard | Show floating keyboard in landscape mode |
| Enable Navigation | Enable R1+D-pad navigation shortcuts |
| Show Hotkey Bar | Display hotkey reference on keyboard |

## Controller Button Mapping

All controller buttons are now fully configurable through the app settings. Tap "Configure Button Bindings" in the main settings to customize each button's action.

### Default Bindings

#### Basic Controls
| Button | Action |
|--------|--------|
| L1 | Toggle voice input (record/stop) |
| A | Ctrl+R (fzf search) |
| B | Enter |
| X | Delete |
| Y | Space |

#### With R1 Modifier (hold R1 + press)
| Combo | Action |
|-------|--------|
| R1+L1 | New tmux pane (horizontal) |
| R1+L2 | New tmux window |
| R1+A | Ctrl+A (tmux prefix) |
| R1+X | Ctrl+C (cancel) |
| R1+Y | Ctrl+D (exit) |
| R1+Up | Home |
| R1+Down | Recent Apps |
| R1+Left | Back |

#### Triggers
| Button | Action |
|--------|--------|
| L2 | Previous tmux window |
| R2 | Next tmux window |

### Available Actions

Actions are grouped into categories:

- **Voice**: Voice Input
- **Keys**: Enter, Space, Delete, Tab, Escape
- **Control**: Ctrl+A through Ctrl+Z (all control characters)
- **Tmux**: New Pane (H/V), New/Next/Previous Window, Next/Previous Pane, Command Mode, Detach, Copy Mode
- **System**: Home, Back, Recent Apps

### Tmux Prefix Key

The tmux prefix key is configurable (default: Ctrl+A). Change it in the button bindings settings to match your tmux configuration.

## Setup

1. Install the app
2. Enable **Whisper to Input** in:
   - Settings → Accessibility → Installed services
   - Settings → System → Keyboard → On-screen keyboard
3. Configure your preferred backend in the app settings
4. For OpenAI API, enter your API key

## Requirements

- Android 11+ (API 30+) for auto IME switching
- Bluetooth controller with L1/R1/L2/R2 buttons
- Microphone permission
- Accessibility service permission
- (Optional) OpenAI API key for network transcription

## Building

```bash
# Set Java 17 (required for Gradle)
export JAVA_HOME="/path/to/jdk17"

# Build debug APK
./gradlew assembleDebug

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Version History

- **v0.63**: Configurable button bindings UI with expandable list, configurable tmux prefix key
- **v0.62**: Auto-switch IME for network-based transcription using SoftKeyboardController API
- **v0.61**: Text injection via WhisperInputService for terminal apps
- **v0.58**: Add Whisper backend support alongside Gboard voice typing
- **v0.57**: Find and click Gboard voice typing button via accessibility tree
