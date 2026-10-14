# Wiki Offline

An Android app for reading Simple English Wikipedia with no internet connection, with an AI
assistant that runs entirely on the phone. Kotlin, Jetpack Compose (Material 3), single activity.
After the one-time download of the content, nothing needs a network connection.

- Articles and images are read straight from a Kiwix ZIM file (`wikipedia_en-simple_all_maxi`,
  3.1 GB) and decompressed in memory on demand; the archive is never unpacked. Internal links
  work; external links ask before leaving the app.
- Search: title suggestions while typing, and full-text search inside articles using the Xapian
  index in the ZIM file. Works with no AI model installed.
- Ask AI: a question in plain language gets a short answer drawn only from the articles, with
  links to the articles it rests on. When the articles do not contain the answer, the app says so
  and shows the plain search results instead.
- First-launch setup downloads the ZIM file (resumable after interruptions or the app being
  killed, with progress, a mobile-data warning, a free-space check and a SHA-256 check) or imports
  one already on the phone. The AI model is set up the same way from the Ask AI screen.
- Bookmarks, reading history, a light/dark switch in the top bar (articles follow it), and a main
  page reflowed to fit a phone screen.
- The ZIM file and models live in app-specific storage, so the app needs no storage permission.

## Current state of your machine

- Your **OnePlus Nord CE 5G** has the **release build** (version 1.0) with the full Wikipedia
  file. **Gemma 3 1B** (`gemma3-1b-it-int4.task`) is the active model; Qwen3.5 0.8B is kept in the
  app's `files/aside/` folder (see [Switching models](#switching-models)). History and bookmarks
  were cleared by the reinstall; the release test left a bookmark on "Volcano", a few history
  entries and the theme set to light.
- The emulator **`wikidev`** (Pixel 7, Android 16 / API 36, x86_64) is shut down. It holds the full
  Wikipedia file and the Qwen model, but has an older debug build (before the globe icon and the
  "Wiki Offline" main page).
- The code is in the private GitHub repository https://github.com/Jebu04/wiki-offline, and the
  APK is attached to the release https://github.com/Jebu04/wiki-offline/releases/tag/v1.0.
  The signing key is not on GitHub.

## The release build

- Built with `.\gradlew.bat :app:assembleRelease`; the APK lands in
  `app/build/outputs/apk/release/app-release.apk`.
- arm64-v8a only. Code shrinking (R8) and resource shrinking are on, and the native libraries are
  stored compressed, which takes the APK from 81 MB to 27.5 MB. Android unpacks them once at
  install.
- It is signed with a key generated for this app: `C:\Users\Jesse\.android-keys\wiki-offline-release.jks`
  (alias `wikioffline`), with its password in `wiki-offline-release.properties` in the same folder.
  The build reads them through `keystore.properties` in the project root. **None of these are in
  git. Back up the `.jks` file and its password somewhere safe** (a password manager or an
  encrypted drive). Android only installs an update over the phone's copy if it is signed with
  this same key; without it, a future version can only be installed by uninstalling, which deletes
  the app's data, including the 3.1 GB Wikipedia file. Certificate SHA-256:
  `89d5a3b61a6435e67b82ae4d4648d96c841128ec1f7b3800646f823067e26393`.
- For a future version, raise `versionCode` (and `versionName`) in `app/build.gradle.kts` before
  building, then install over the old one with `adb install -r`.
- Debug and release builds are signed with different keys, so going from one to the other on a
  device needs an uninstall. Move `files/zim`, `files/models` and `files/aside` out of
  `/sdcard/Android/data/com.offlinewiki.simple/` first and back after reinstalling, or the content
  has to be downloaded again.
- Before release, the release APK itself was walked through on your phone: main page, title
  suggestions, full-text search, opening a result, the light/dark switch, bookmarking, history,
  and three AI questions (two answered with sources, one refused). No crashes.

## Build and run

Requirements: JDK 17 and the Android SDK (both already on this machine; the JDK path is set in
`gradle.properties`). `local.properties` points at the SDK. In PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug          # APK: app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:testDebugUnitTest      # JVM unit tests
```

The debug build also contains x86_64 libraries so it runs on the emulator. Start the emulator
and install:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$sdk\emulator\emulator.exe" -avd wikidev
& "$sdk\platform-tools\adb.exe" -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
```

Android Studio 2022.1 on this machine is too old to open the project (it uses Android Gradle
Plugin 9.4 and Gradle 9.6); build from the command line or update Android Studio.

For development, the setup screen of a debug build also offers a 3 MB test file (the Ray Charles
ZIM). In debug builds the AI pipeline logs its search queries, chosen passages, raw model output
and timings under the logcat tag `AiSearch`.

### Switching models

There is no model picker in the app; one model sits in `files/models/` at a time. To switch your
phone from Gemma to Qwen, from Git Bash with the phone connected:

```bash
export MSYS_NO_PATHCONV=1
D=/sdcard/Android/data/com.offlinewiki.simple/files
adb shell am force-stop com.offlinewiki.simple
adb shell "mv $D/models/gemma3-1b-it-int4.task $D/aside/ && mv $D/aside/Qwen3.5-0.8B_int8.litertlm $D/models/"
```

Swap the two file names around to go back to Gemma.

Or, in the app: Ask AI → Remove this model, then import the other file from the phone's storage.
A `.task` model file must keep its `.task` extension or the runtime refuses it.

## What was tested, and the results

All results are from the final code unless marked otherwise.

| Suite | Where | Tests |
|---|---|---|
| `AiSearchLogicTest`: keywords and stemming, query parsing, HTML to passages, passage selection and budget, the article coverage gate, "not found" and label stripping, loop detection, grounding checks (numbers, one passage per sentence, names, subject) | JVM | 10 |

**10 tests, 0 failures.** There are no instrumented or UI tests; everything else was checked by
hand.

### Checked by hand

- **Setup** (emulator): download of the tiny test file and of the full 3.1 GB file, including
  killing the app mid-download, resuming after relaunch, pause and resume, and the SHA-256 check;
  import of a ZIM file through the system picker, and rejection of a file that is not a ZIM.
- **Reading** (emulator in airplane mode, and your phone): main page, articles with images,
  internal links, back navigation, the dialog for external links, random article, and restoring
  the open article after a theme change or the app being killed.
- **Search** (both): suggestions, full-text results with highlighted snippets, opening a result,
  the empty-result message, and "Ask AI" from the search screen.
- **AI search on your phone**, ten questions, seven of which the articles answer:

  | | Qwen3.5 0.8B (int8) | Gemma 3 1B (int4) |
  |---|---|---|
  | Time per answered question | 16–40 s | 13–20 s |
  | First question after opening the app | about 70 s | about 23 s |
  | Memory with the model loaded | about 2.5 GB | 1.1–1.7 GB |
  | Answerable questions (7) | 6 correct, 1 refused | 4 correct but terse, 3 refused |
  | Unanswerable questions (3) | 3 refused | 3 refused |

  No wrong answer was shown in the final runs of either model. The Gemma column is from the debug
  build with the final prompt; the release build repeated three of the questions with the same
  results.
- **Model loading**: lazy loading on the first question, the `.task` and `.litertlm` formats, and
  unloading when Android reports low memory. The GPU backend was tried on your phone and got the
  app killed for using too much memory during loading, so the app uses the CPU.
- **Main page and top bar**: the reflowed main page at your phone's width, the "Wiki Offline"
  title, the removed footer note, the light/dark switch and that it survives a restart, the
  bookmark item in the ⋮ menu, and the new launcher icon in the app drawer.

## Unfinished, untested or known to be imperfect

- **AI answers are slow and sometimes thin.** Each answer takes 15–30 seconds on your phone, and
  the first one after opening the app waits for the model to load. The checks that keep made-up
  answers out also refuse some questions the articles do answer (the Sun's temperature, the
  Eiffel Tower's height with Gemma). Gemma tends to answer with a bare fragment such as "Canberra".
- **Checks are keyword-based, not semantic.** An answer that uses the passages' words but says
  something they don't can still get through; none did in the final test runs, but an earlier
  version showed several.
- **The in-app model download is Qwen.** Gemma needs a Hugging Face login and licence acceptance,
  so it can only be imported. The model download has no checksum check (the ZIM download has one).
- **The in-app download of the ZIM file was only tested on the emulator.** Your phone got its files
  over USB because it was on mobile data. The mobile-data warning, the not-enough-space message and
  automatic retry after a dropped connection have not been seen working.
- **No network check on the phone.** That the app fetches nothing from the network was checked on
  the emulator in airplane mode only.
- **History and bookmarks are not backed up** and were lost when the debug build was replaced.
- **The main-page reflow and the footer removal depend on the current Kiwix file's layout.** A
  future ZIM with a different main page may need the injected CSS and script adjusted.
- **Licences.** The app bundles Kiwix's libzim and libkiwix, which are GPL-3.0. Distributing the
  APK publicly would require releasing this app's source under a GPL-compatible licence. While the
  repository and release stay private this does not apply. Article pages keep Wikipedia's
  CC BY-SA attribution note; only the main page's copy was removed.
- Not tested on a tablet, in landscape, with TalkBack, or on Android versions other than 13
  (phone) and 16 (emulator).

## Project layout

```
app/src/main/java/com/offlinewiki/simple/
  App.kt, MainActivity.kt   app container, theme choice, screen switching
  zim/       ZimRepository: opens the ZIM, reads entries, suggestions and full-text search (libkiwix)
  data/      content files on disk, bookmarks and history, theme setting
  data/transfer/   resumable downloads and imports, the foreground service, download sources
  llm/       LlmEngine interface, LiteRT-LM implementation, lazy loading and unloading
  ai/        the AI search pipeline: query rewrite, retrieval, passages, prompts, answer checks
  ui/        one folder per screen: setup, reader (WebView served from the ZIM), search, ask,
             saved (bookmarks and history), info (content file), theme
app/src/test/   JVM unit tests for the AI pipeline logic
```
