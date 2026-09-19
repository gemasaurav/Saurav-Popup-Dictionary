# Saurav Popup Dictionary — Android project

Adds **Saurav Popup Dictionary** to the text-selection menu (next to Copy / Select all /
Web search). Select any word anywhere on the phone → tap it → a glass popup shows the
Hindi meaning, English definitions, IPA, pronunciation, synonyms and antonyms.

## How it works

The magic is one intent filter in `AndroidManifest.xml`:

```xml
<intent-filter>
    <action android:name="android.intent.action.PROCESS_TEXT" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/plain" />
</intent-filter>
```

Android 6.0+ scans installed apps for this filter and puts each one into the floating
selection toolbar automatically. No accessibility service, no overlay permission,
no special setup by the user.

`PopupDictionaryActivity` reads `Intent.EXTRA_PROCESS_TEXT`, looks the word up, and
draws itself over the host app using a transparent, non-floating dialog theme.

## Build it

1. Install **Android Studio** (any recent version).
2. `File → Open` → choose this `SauravPopupDictionary` folder.
3. Let it sync. When it asks about the Gradle wrapper, accept — the wrapper JAR is not
   included here, so Android Studio (or `gradle wrapper` on the command line) will
   generate it on first sync.
4. `Build → Build Bundle(s)/APK(s) → Build APK(s)`.
5. The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone
   and install (you'll need "Install unknown apps" enabled for your file manager).

Command line alternative, once the wrapper exists:

```bash
./gradlew assembleDebug
```

## Using it

1. Open the app once (optional — it just shows the instructions page).
2. Go to any app: Chrome, WhatsApp, Gmail, notes.
3. Long-press a word.
4. Tap **Saurav Popup Dictionary** in the menu. If you don't see it, tap the **⋮**
   overflow at the end of the toolbar — Android hides longer labels there.

## Things you may want to change

| What | Where |
|---|---|
| Menu label (shorter = more likely to show without the ⋮ overflow) | `res/values/strings.xml` → `process_text_label` |
| Package / app ID | `app/build.gradle` → `applicationId`, plus the `package` line in the two `.kt` files |
| Offline word list | `PopupDictionaryActivity.kt` → `object Builtin` |
| Popup colours and corner radius | `res/drawable/bg_card.xml` |
| The fake status bar (clock / 5G / battery) on the home screen | `app/src/main/assets/dictionary.html` — it was a mockup detail; delete the `.status-bar` div for a real build |

## Notes and limits

- **Works** in apps using the standard Android text selection: Chrome, WhatsApp, Gmail,
  Telegram, Instagram, most readers and note apps.
- **Won't appear** in apps that draw their own selection toolbar (some PDF readers, a few
  games, apps that block `PROCESS_TEXT`). The app also registers for the share sheet
  as a backup there.
- Definitions come from `freedictionaryapi.com`, with `api.suvankar.cc` as fallback and
  MyMemory for the Hindi translation. Results are cached in `SharedPreferences`, so words
  you've already seen open instantly and work offline.
- Pronunciation uses the API's audio when present, otherwise falls back to the phone's
  built-in text-to-speech — which works with no internet at all.
- `minSdk` is 23 because `ACTION_PROCESS_TEXT` was introduced in Android 6.0.
