# Triggered Whisper

Triggered Whisper is a controller-optimized fork of [Whisper To Input](https://github.com/j3soon/whisper-to-input), designed for hands-free operation with Bluetooth game controllers. Perfect for AR displays and terminal workflows.

## Key Features

- **Bluetooth Controller Support**: Full button mapping for game controllers (tested with 8BitDo micro BT)
- **Modifier Key System**: R1 acts as a modifier for advanced combos
- **tmux Integration**: Built-in shortcuts for tmux window/pane management
- **Compact UI**: Minimal keyboard layout optimized for controller-only use
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
- Compact horizontal keyboard layout (~40dp height)
- Full Bluetooth controller support with modifier system
- Debug display showing last 2 key events

## License

This repository is licensed under the GPLv3 license. For more information, please refer to the [LICENSE](android/LICENSE) file.

Forked from [Whisper To Input](https://github.com/j3soon/whisper-to-input) by Johnson Sun ([@j3soon](https://github.com/j3soon)).

Original Contributors: Yan-Bin Diau ([@tigerpaws01](https://github.com/tigerpaws01)), Johnson Sun ([@j3soon](https://github.com/j3soon)), Ying-Chou Sun ([@ijsun](https://github.com/ijsun))

Controller support added by [@ekg](https://github.com/ekg).
