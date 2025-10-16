# Triggered Whisper

Triggered Whisper is a controller-optimized fork of [Whisper To Input](https://github.com/j3soon/whisper-to-input), designed for hands-free operation with Bluetooth game controllers. Perfect for AR displays and terminal workflows.

## Key Features

- **8BitDo Micro Bluetooth Controller Support**: Optimized for the 8BitDo Micro Bluetooth Gamepad, a tiny portable controller perfect for AR workflows
- **Floating Keyboard Mode**: In landscape, keyboard displays as a draggable overlay window that stays on top, allowing you to position it anywhere on screen
- **Modifier Key System**: R1 acts as a modifier for advanced combos with visual feedback
- **tmux Integration**: Built-in shortcuts for tmux window/pane management (Ctrl+Q based)
- **Compact UI**: Minimal keyboard layout (25% shorter in landscape without floating mode)
- **Side-by-side Installation**: Installs alongside original Whisper To Input app

## Installation

Download the latest APK: `http://hypervolu.me/www/triggered/triggered-whisper.apk`

Follow the same installation steps as [Whisper To Input](https://github.com/j3soon/whisper-to-input#installation), but select "Triggered Input" as your keyboard instead of "Whisper Input".

## Controller Button Mappings

### Basic Controls
- **L1** = Toggle recording (listen/stop)
- **Button B** = Enter/return
- **Button X** = Delete/backspace
- **Button Y** = Space
- **Button A** = Ctrl+R (fzf autocomplete)

### R1 Modifier Combos
- **R1 (hold)** = Modifier mode (shows "🔧 MOD")
- **R1+L1** = New tmux pane - horizontal split (Ctrl+Q ") - shows "➕ NEW PANE"
- **R1+A** = Ctrl+Q only (puts tmux in command mode for arrow navigation) - shows "⌨️ CTRL+Q"
- **R1+X** = Ctrl+D (exit/logout) - shows "🚪 CTRL+D"
- **R1+Y** = Ctrl+C (cancel/interrupt) - shows "❌ CTRL+C"

### Trigger Buttons
- **L2** = Ctrl+Q P (tmux previous window) - shows "◀️ PREV"
- **R2** = Ctrl+Q N (tmux next window) - shows "▶️ NEXT"

### Visual Feedback
All commands show emoji indicators in the debug display (green text area on left side of keyboard), so you can see exactly what button was pressed.

## Floating Keyboard Mode

The floating keyboard feature allows the keyboard to appear as a draggable overlay window in landscape mode:

### Setup
1. Open Triggered Whisper settings
2. Enable "Floating Keyboard in Landscape"
3. Click "Apply" - this will automatically open Triggered Whisper's app permissions page
4. Scroll down and tap "Display over other apps"
5. Enable the permission
6. Return to your app and switch to landscape mode

### Features
- **Draggable**: Touch and drag any non-button area to reposition the window
- **Portrait Dimensions**: Maintains full portrait keyboard size (no 25% reduction)
- **Stable Layout**: Cancel button always visible to prevent layout reorganization
- **Persistent**: Stays on top of other apps, ideal for terminal workflows

### Quirks
- Permission must be granted manually through Android settings (cannot be requested via standard permission dialog)
- When enabled, the floating window appears automatically in landscape orientation
- Keyboard dimensions are locked while floating to prevent size changes during recording/transcribing
- The keyboard appears as a title bar/overlay that can be moved to any edge of the screen

## 8BitDo Micro Controller Notes

The **8BitDo Micro Bluetooth Gamepad** is a tiny, portable controller perfect for this use case:
- Ultra-compact size (fits on a keychain)
- Rechargeable via USB-C
- All standard gamepad buttons (face buttons, triggers, bumpers)
- Excellent Bluetooth connectivity
- Perfect for AR glasses + terminal workflows

### Connection
1. Put controller in pairing mode (hold Start + Y)
2. Connect via Android Bluetooth settings
3. Controller buttons will be immediately recognized by Triggered Whisper

## Use Cases

- **AR Displays**: Phone in pocket, viewing terminal on AR glasses
- **Terminal Workflows**: Quick tmux navigation and voice input
- **Hands-free Operation**: Navigate and input without touching the phone

## Services

Triggered Whisper supports the same STT/ASR backends as Whisper To Input:
- OpenAI API
- Whisper ASR Webservice (self-hosted)
- NVIDIA NIM (self-hosted)

See the [original documentation](https://github.com/j3soon/whisper-to-input#services) for configuration details.

## Developer Notes

### Building from Source

```sh
cd android
./gradlew assembleDebug
```

The APK will be in `app/build/outputs/apk/debug/app-debug.apk`.

### Testing Method

Upload to testing server:
```sh
scp app/build/outputs/apk/debug/app-debug.apk hypervolu.me:www/triggered/triggered-whisper.apk
```

Then download on device from: `http://hypervolu.me/www/triggered/triggered-whisper.apk`

### Repository Setup

```sh
# Set new origin
git remote rename origin upstream
git remote add origin git@github.com:ekg/triggered-whisper.git

# Push to new repo
git push -u origin feature/bluetooth-controller-support
```

## Changes from Upstream

- Package ID: `com.example.whispertoinput.controller`
- App name: "Triggered Whisper"
- Keyboard name: "Triggered Input"
- Compact horizontal keyboard layout (25% height reduction in landscape, or full size in floating mode)
- Full Bluetooth controller support with modifier system (8BitDo Micro tested)
- Floating keyboard mode with draggable overlay window
- Debug display showing last 2 key events with emoji feedback
- Always-visible cancel button for stable layout
- Dimension locking system to prevent size changes during state transitions

## License

This repository is licensed under the GPLv3 license. For more information, please refer to the [LICENSE](android/LICENSE) file.

Forked from [Whisper To Input](https://github.com/j3soon/whisper-to-input) by Johnson Sun ([@j3soon](https://github.com/j3soon)).

Original Contributors: Yan-Bin Diau ([@tigerpaws01](https://github.com/tigerpaws01)), Johnson Sun ([@j3soon](https://github.com/j3soon)), Ying-Chou Sun ([@ijsun](https://github.com/ijsun))

Controller support added by [@ekg](https://github.com/ekg).
