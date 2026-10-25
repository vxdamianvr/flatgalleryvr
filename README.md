# Flat Gallery VR (FlatGallery)

> **Ambient, multi-window 2D digital picture frames for Meta Quest & Horizon OS.**  
> Designed to run seamlessly in the background alongside **Virtual Desktop**, browsers, and productivity apps without stealing VR focus.

---

## 💡 Intuition Behind the Design

### The Problem with Traditional VR Image Viewers
Most VR photo galleries are designed as full-screen, immersive 6DoF spatial applications. While immersive 3D/panoramic viewers have their place, they suffer from a fundamental flaw when you just want ambient visuals:
* **Exclusive Focus Lock:** Launching a full VR gallery app forces Horizon OS to suspend or kill other spatial tasks—you cannot use your PC via **Virtual Desktop**, browse the web, or chat on Discord while an immersive VR app is active.
* **Heavy Resource Footprint:** Game-engine-based viewers (Unity/Unreal) consume significant battery, GPU, and RAM simply to render a flat rectangle on a virtual wall.
* **Aspect Ratio Distortion:** Conventional viewers frequently letterbox images into fixed black bars or stretch portrait artwork onto landscape screens.

### The Solution: Native 2D Horizon OS Frames
**Flat Gallery** is built as an ultra-lightweight, 100% native Android 2D panel application registered with Horizon OS (`com.oculus.intent.category.2D`). 

Instead of acting as a standalone virtual world, Flat Gallery acts as **digital interior decor for your virtual room**:
1. **Coexists with Virtual Desktop:** It floats natively in your Horizon OS space alongside your virtual computer monitor, browser, or media player.
2. **Multi-Window "Frames":** You can spawn multiple independent window instances across your physical room—place a tall portrait frame beside your desk, an ultra-wide landscape frame above your monitor, and a square frame on your side table.
3. **Aspect-Ratio Purity:** Images are pre-sorted into **Portrait**, **Landscape**, and **Square** buckets. Each window locks to an aspect category so photos fill their frame edge-to-edge with no awkward pillarboxing or distorting stretches.
4. **Synchronized Global State:** All active frames share favorites and night mode dimming levels in real-time.

---

## 🚀 The Ideal Userflow

```
[1. Select Folders] ──▶ [2. Auto-Scan & Sort] ──▶ [3. Pick Shape & Resize] ──▶ [4. Spawn New Frames] ──▶ [5. Ambient Slideshow]
         │                                                    │                              │
         ▼                                                    ▼                              ▼
Built-in Double-Pane                            Portrait / Landscape / Square           Multiple independent frames
  Folder Explorer                                    Edge-to-Edge Display                 placed around your room
```

1. **First Launch & Folder Selection:**
   * The user opens Flat Gallery from the Quest Horizon OS dock.
   * A clean setup wizard prompts them to choose media folders using the built-in **Double-Pane File Explorer**.
   * Parent directories (e.g. `/sdcard/Pictures/Wallpapers` or `/sdcard/Pictures/Photos`) can be selected with one click, while specific subfolders can be selectively excluded.
2. **Instant Dimension Analysis:**
   * The app rapidly scans image headers in the background without loading heavy bitmaps into memory, calculating precise aspect ratios.
   * Photos are categorized into three pools: **Portrait** ($w/h < 0.95$), **Landscape** ($w/h > 1.05$), and **Square** ($0.95 \le w/h \le 1.05$).
3. **Shape Assignment & Window Placement:**
   * The user designates the frame's shape (e.g., *Portrait*).
   * Using the Quest controller's grab bar, the user shapes and scales the Horizon OS window to match their desired proportions and pins it in physical space.
4. **Spawning Companion Frames:**
   * Clicking the `❐` (New Frame) icon launches an independent window instance.
   * The user sets the second window to *Landscape*, positions it over their Virtual Desktop workspace, and repeats for as many ambient frames as desired.
5. **Hands-Free Ambient Viewing:**
   * Slideshow playback begins immediately with **Smart Shuffle** enabled by default.
   * On-screen controls automatically fade out after a brief moment of inactivity, leaving pure, edge-to-edge artwork.
   * Tapping any image gently restores the minimalist controls.

---

## ✨ Core Features

### 🖼️ Aspect-Ratio Smart Sorting
Never see a tall smartphone wallpaper squished into a wide monitor frame. Flat Gallery inspects image dimensions and routes them to matching frame shapes:
* **Portrait Mode:** Tailored for vertical photography, mobile captures, and portrait-oriented media.
* **Landscape Mode:** Tailored for desktop wallpapers, photography, and panoramic compositions.
* **Square Mode:** Tailored for 1:1 profile art and album covers.

### 🔀 Smart Diversity Shuffle
Traditional random shuffle algorithms often repeat the same artist or show several images from the same subfolder consecutively. Flat Gallery's **Smart Diversity Shuffle** prioritizes visual variety:
* Tracks recently displayed images and containing folders.
* Ensures the first image on launch is unique across runs.
* Prevents clustering of photos from the same subdirectory.

### ⭐ Folder Favoriting
Designed for seamless cross-aspect collections:
* **Favorite Single Image:** Stars the individual photo for quick recall.
* **Favorite Entire Folder:** Stars all images residing in that folder across *all* aspect ratios simultaneously.
  * *Example:* If you have a folder named `Cyberpunk` containing both landscape wallpapers and portrait illustrations, starring the folder in a landscape frame automatically makes the matching portrait images from that same folder available to any portrait frames!
* **Global Synchronization:** Favoriting or un-favoriting reflects instantly across all open frame windows.

### 🌙 Synchronized Ambient Night Mode
Watching a movie or working in Virtual Desktop with the virtual lights off? Bright digital picture frames can cause glare:
* Tap the Moon icon (`☽`) to toggle Night Mode: **Off ➔ Light ➔ Medium ➔ Dark ➔ Off**.
* The dimming overlay synchronizes across **all active frame windows** simultaneously.

### 📁 Custom Double-Pane File Explorer
* Designed specifically for VR laser pointer interaction.
* Left pane: Interactive folder hierarchy tree.
* Right pane: Direct folder contents with instant recursive image counts and checkbox selection.
* Includes recursive subfolder inheritance with individual child exclusion overrides.

### 📐 Dynamic Responsive UI
* Automatically transitions between **Wide** and **Compact** UI layouts based on the window's current dp dimensions.
* Segmented horizontal buttons neatly fold into compact dropdown spinners on narrow panels.
* Control bar glyphs adjust spacing to preserve balanced symmetry.

---

## 🎮 Controls & Interface Reference

| Glyph | Action | Description |
| :---: | :--- | :--- |
| `≡` | **Settings Menu** | Adjust Frame Shape, Source (All vs. Favorites), Transition Effect, and Ken Burns animation. |
| `☆` / `★` | **Favorite Button** | Opens prompt to Favorite/Unfavorite the current photo or its entire containing directory. |
| `☽` | **Night Mode** | Cycles synchronized dimming levels (Off, Light, Medium, Dark) across all frames. |
| `❐` | **New Frame** | Spawns a brand-new, independent Horizon OS frame window. |
| `◀` / `▶` | **Navigation Arrows** | Manually skip to previous or next photo in the queue. |
| `⏱` | **Interval Timer** | Cycles slide intervals: `1s`, `3s`, `15s`, `30s`, `1m`, `5m`, `15m`, `30m`, `1h`. |
| `⏮` / `⏭` | **Prev / Next** | Bottom dock controls for sequence navigation. |
| `▶` / `⏸` | **Play / Pause** | Toggle automatic slideshow progression. |
| `⇄` / `↺` | **Shuffle / Order** | Toggle between Smart Diversity Shuffle and linear folder playback. |

---

## 🛠️ Technical Architecture

* **Platform:** Meta Quest (Quest 2, Quest Pro, Quest 3, Quest 3S)
* **OS:** Meta Horizon OS (Android 10 - 14 / API 29 - 34)
* **Stack:** 100% Native Kotlin & Android SDK (No Unity, No Unreal Engine, No React Native)
* **Window Category:** `com.oculus.intent.category.2D`
* **Multi-Instance Windowing:** `FLAG_ACTIVITY_NEW_DOCUMENT` + `FLAG_ACTIVITY_MULTIPLE_TASK`
* **Image Pipeline:** Subsampled bitmap decoding (`inJustDecodeBounds` + `inSampleSize`) with custom memory LRU caching to guarantee butter-smooth 90/120Hz system frame rates without memory leaks.

---

## 📦 Building & Installing

### Prerequisites
* Android Studio (Koala or newer) or Command Line Tools
* Android SDK (API 34, Build Tools 34.0.0)
* Meta Quest headset with **Developer Mode** enabled
* ADB connected via USB-C or Wireless ADB

### 1. Clone the Repository
```bash
git clone https://github.com/vxdamianvr/flatgalleryvr.git
cd flatgalleryvr
```

### 2. Build Debug APK
Using Gradle wrapper:
```bash
# On Windows PowerShell
.\gradlew assembleDebug

# On macOS / Linux
./gradlew assembleDebug
```

### 3. Install to Quest Headset
Ensure your headset is listed under `adb devices`:
```bash
adb devices
```
Install directly via Gradle:
```bash
.\gradlew installDebug
```
Or manually install the generated APK:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. Launch Flat Gallery
Launch directly from your Quest's **App Library ➔ Unknown Sources**, or trigger via ADB:
```bash
adb shell am start -n com.yourname.flatgallery/.MainActivity
```

---

## 📄 License
Distributed under the MIT License. See `LICENSE` for more information.
