# Cog

A privacy-first replacement for the default Android assistant (Gemini). It's built on
FastSearch, an earlier search-bar app: say **“ok Cog”** or invoke the assistant gesture and
the frosted search bar drops in, keyboard up and microphone already listening. Speak or type, and
Cog either searches (apps, contacts, maths, web) or **does what you asked and tells you
so out loud**, in its own voice: sets alarms and timers, plays music on Spotify,
reads the weather, answers "what is…" from Wikipedia, toggles the flashlight, controls volume and
media, converts units, tells the time anywhere, and calls and texts people. In English and Greek.

**No AI answers anything.** Requests are understood with deterministic rules on the device, and
every reply is a fixed sentence template filled in with facts: "Splendid choice. Playing Iron
Maiden on Spotify." "Right now in London it is 18 degrees and partly cloudy." The same words
always do the same thing and get the same kind of answer. (Speech recognition, the wake word and
text-to-speech are the usual on-device speech engines; they turn sound into text and back, nothing
more.)

## What you can say

| Area | English | Ελληνικά | How it runs |
|---|---|---|---|
| Music | `play iron maiden on spotify`, `put on lofi beats` | `παίξε Iron Maiden στο Spotify`, `βάλε μου λίγο Χατζιδάκι` | opens Spotify on the artist/song/album/saved playlist and presses play (optional helper), else `MEDIA_PLAY_FROM_SEARCH` |
| Player | `pause`, `next song`, `what's playing`, `like this song`, `shuffle on`, `repeat this song` | `παύση`, `επόμενο τραγούδι`, `τι παίζει`, `μου αρέσει αυτό το τραγούδι`, `τυχαία αναπαραγωγή` | the active player's media session, or media keys |
| Wikipedia | `what is a quasar`, `who was ada lovelace` | `τι είναι κβάζαρ`, `ποιος ήταν ο Καζαντζάκης` | Wikipedia in the assistant's language, then English |
| Weather | `weather tomorrow in paris`, `will it rain tomorrow` | `τι καιρό θα κάνει αύριο στην Αθήνα`, `θα βρέξει;` | Open-Meteo (keyless), coarse location |
| Alarms | `alarm 7am`, `wake me up in 30 minutes` | `ξυπνητήρι στις 7 και μισή`, `ξύπνα με σε 20 λεπτά` | `AlarmClock.ACTION_SET_ALARM` |
| Timers | `timer 5 min`, `10 minute pasta timer` | `χρονόμετρο μιάμιση ώρα`, `χρονόμετρο 10 λεπτά για τα μακαρόνια` | `AlarmClock.ACTION_SET_TIMER` |
| World clock | `time in tokyo` | `τι ώρα είναι στη Νέα Υόρκη` | `java.time`, offline |
| Conversions | `5 km to miles` | `πόσα κιλά είναι 10 λίβρες`, `5 χλμ σε μίλια` | offline table |
| Maths | `12*(3+4)` | `πόσο κάνει 12 επί 7` | offline calculator |
| Device | `flashlight`, `volume 40%`, `mute` | `άναψε τον φακό`, `ένταση στο 40`, `σίγαση` | `CameraManager`, `AudioManager` |
| People | `call mom`, `text alex saying on my way` | `πάρε τη μαμά`, `στείλε μήνυμα στον Γιώργο ότι έρχομαι` | dialer / SMS app, pre-filled |
| Apps & places | `open spotify`, `navigate to the airport` | `άνοιξε το Viber`, `πήγαινέ με στο αεροδρόμιο` | launcher, `geo:` intent |
| Switches | `turn on wifi`, `do not disturb` | `άνοιξε το wifi`, `μην ενοχλείτε` | Settings panels (see Limitations) |

Greek is matched without accents or case ("ξυπνητηρι" = "Ξυπνητήρι"), while names, songs and
places keep their accents. An English sentence with a Greek name ("play Βασίλης Παπακωνσταντίνου
on spotify") still works.

## Voice in, voice out

**Listening.** The overlay opens with the keyboard up *and* the microphone listening (*Settings ›
Listen when opened*). The mic button pulses with your voice and the transcript appears live in the
search bar. Start typing and it stops listening. When you finish speaking, the command runs by
itself. Speech recognition is the phone's own service, in-process (no popup), preferring its
offline model.

**Wake word.** *Settings › Wake word* listens for **“ok Cog”** (or any English phrase you type)
with an offline [Vosk](https://alphacephei.com/vosk/) model, downloaded once (41 MB, checked
against a fixed SHA-256). Audio is decoded in memory and never stored or sent. It runs as a
microphone foreground service with a notification and the system mic indicator, and pauses while
the overlay is open. It needs *display over other apps* to pop up over other apps (otherwise it
posts a "tap to talk" notification). On the lock screen it asks you to unlock first. After a
reboot Android won't let it start listening by itself, so it posts a one-tap reminder.

**Speaking.** *Settings › Voice*: **Cog** is the assistant's own voice, a voice model trained on
the PC (see [The Cog voice](#the-cog-voice)) that runs on the phone, offline. It speaks English;
in Greek it falls back to **Robot Cog**. The other styles, Robot Cog (polite
robot with a metallic shimmer), Natural, Classic robot, Tiny bot and Big bot, each **Male** or
**Female**, are the phone's offline text-to-speech, reshaped with classic DSP (flanger, ring modulation, comb filter). Engines don't
say which voices are male, so each voice's pitch is measured once on the phone; if there's no
voice of the wanted kind (Google's Greek voice is female), the voice is resampled to move its
formants and pitch, with the engine speaking faster to keep the tempo. *Base voice* lists the
phone's voices labelled male/female. *Spoken replies* chooses when it talks: always, after voice
only, or never.

## The Cog voice

The *Cog* voice is a [Piper](https://github.com/OHF-Voice/piper1-gpl) (VITS) model, run on the
phone by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) with espeak-ng for pronunciation.
It lives in `app/src/main/assets/cog/` (`cog.onnx`, `tokens.txt`, English `espeak-ng-data`).
It is made on the PC from a short reference recording, by the scripts in `voice/`:

1. `transcribe.py`: Whisper transcribes the cut lines (`work/segs/`, checked by hand).
2. `make_corpus.py` + `clone.py`: F5-TTS clones the voice from those lines and reads about
   1,700 sentences in it: the assistant's own replies filled with varied values, the CMU ARCTIC
   prompts, and encyclopedia-style sentences. `check_clips.py` drops clips Whisper can't match
   to their text.
3. `train.sh`: fine-tunes the British *alan* Piper voice on them in Docker (GPU; `Dockerfile`).
4. `package_model.py --install` adds sherpa-onnx's metadata and token list and copies the model
   into the app; `try_voice.py` speaks test lines through sherpa-onnx on the PC.

The reference recording, the generated training data and the trained `cog.onnx` are not part of
the repository. Without the model the app builds and runs as usual and speaks with Robot Cog.

## Spotify

"Play X on Spotify" always opens Spotify and starts the music:

- **Artists, songs, albums** ("play iron maiden", "play fear of the dark by iron maiden", "play the
  album powerslave", "παίξε τραγούδια του Μάλαμα"): the Spotify ID is looked up on Wikidata (open
  data, no account or key; found IDs are kept on the phone) and Spotify opens that page.
- **Pressing play**, two ways, since Spotify doesn't start a page opened by another app (the old
  `spotify:...:play` link suffix is ignored):
  1. **Spotify connection** (recommended): each user adds *their own* Client ID under *Settings ›
     Spotify connection*, and the app starts the page through Spotify's official App Remote SDK
     (`app/libs/spotify-app-remote-release-0.8.0.aar`). *Connect* first asks for the user's OK with
     Spotify's sign-in library (`spotify-auth-release-2.1.0.aar`) from the settings screen: App
     Remote's own "allow" screen is opened from Spotify's background service, which recent Android
     versions block, and the connection then never answers. The settings screen walks through it:
     create a free app at developer.spotify.com/dashboard (tick Android), add the redirect URI
     `bestassistant://spotify-callback`, the package name `com.bestassistant.app` and the APK's
     SHA1 fingerprint (all shown with copy buttons), paste the Client ID, tap Connect and allow it
     in Spotify. Since Feb 2026 Spotify requires the registering account to have Premium and
     allows up to 5 users per Client ID, which is why every user brings their own.
  2. **Press play in Spotify** (fallback): an accessibility helper limited to Spotify's windows.
     For up to 10 seconds after a request it finds the page's play button (not the mini player)
     and taps it once, unless the page already shows Pause, waiting until the page shows the
     requested name. On Android 13+ a sideloaded app needs *App info › ⋮ › Allow restricted
     settings* first.

  With neither, Spotify opens on the right page and you tap play.
- **Your playlists** ("play my road trip playlist", "βάλε τη λίστα μου ταξίδι"): Spotify doesn't let
  other apps list them without its Web API, so save the ones you want under *Settings › Spotify
  playlists* (Share › Copy link in Spotify, then Add playlist). "Play my liked songs" opens Liked Songs.
- **Anything else**, or when the lookup is slow or offline: `MEDIA_PLAY_FROM_SEARCH` with what kind of
  thing was asked for (artist, song, album, playlist), then Spotify's own search as a last resort.

With **media control** allowed (*Settings › Media control*, which Android calls "notification
access"; notifications are never read), the assistant also checks that something new started
playing and, if not, asks Spotify's media session directly. "What's playing", "like this song",
shuffle and repeat use the media session too, for Spotify and other players.

Queueing, playing on another device or reading your library would need Spotify's Web API on top
of the Client ID. That isn't built.

## Build and install

Requirements: JDK 17+ and the Android SDK (platform 37). The Gradle wrapper is included.

```bash
./gradlew assembleRelease
```

Release builds are signed with `keystore/bestassistant-release.jks` when `keystore/keystore.properties`
exists (both are kept out of version control; **back the folder up**: without it you can't ship an
update that installs over the shared APK, and everyone's Spotify registration would need a new
fingerprint). Without it they fall back to the debug key. Its SHA1 fingerprint is
`92:58:28:4D:84:63:42:62:30:A4:D3:88:9B:91:D8:89:A7:DE:C7:22`; the app also shows it in
*Settings › Spotify connection*.

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

```bash
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.bestassistant.app
```

`./gradlew testDebugUnitTest` runs the unit tests: the English and Greek command parsers
(hundreds of phrases, including ones that must stay ordinary searches), the wake-phrase matcher,
the pitch detector and voice reshaping, the robot-voice DSP and WAV reader, Wikipedia parsing,
the calculator and the fuzzy matcher.

The release APK is about 100 MB, almost all of it native speech libraries (the wake word and the
on-device voice) for three CPU types (arm64, armv7, x86_64).

## How it works

```
app/src/main/java/
├── com/bestassistant/
│   ├── commands/       Pure Kotlin, unit-tested
│   │   ├── CommandParser.kt        English: text -> Command (or null = ordinary search)
│   │   ├── GreekCommandParser.kt   Greek: the same Commands, accent-insensitive
│   │   ├── Command.kt              the command model, music apps
│   │   ├── Spoken.kt               numbers, durations, clock times
│   │   ├── UnitConverter.kt        offline unit conversion
│   │   └── WorldClock.kt           place -> time zone
│   ├── language/AppLanguage.kt     assistant language: resources, formatting, speech
│   ├── listen/VoiceInput.kt        in-process speech recognition, live transcript, mic level
│   ├── wake/
│   │   ├── WakePhrase.kt           finds the wake phrase in a transcript (tested on recordings)
│   │   ├── WakeModel.kt            downloads and verifies the offline model
│   │   └── WakeWordService.kt      microphone foreground service, opens the overlay
│   ├── media/
│   │   ├── MediaControl.kt         media sessions: like, shuffle, now playing, play fallback
│   │   ├── SpotifyCatalog.kt       artist/song/album -> Spotify ID via Wikidata
│   │   ├── SavedPlaylists.kt       the user's saved Spotify playlists
│   │   ├── SpotifyRemote.kt        Spotify App Remote with the user's own Client ID
│   │   ├── SpotifyPlayService.kt   optional helper that taps Spotify's play button
│   │   └── MusicNames.kt           name matching (Greek endings) and Spotify link parsing
│   ├── speech/
│   │   ├── Responder.kt            the reply template for every scenario
│   │   ├── Speaker.kt              text-to-speech, voice choice, streaming playback
│   │   ├── CogVoice.kt             Cog's voice model on the phone (sherpa-onnx)
│   │   ├── VoiceStyle.kt           voice styles and male/female
│   │   ├── RobotVoice.kt           robot effects and the wake chirp
│   │   ├── Pitch.kt                pitch measurement and male/female resampling
│   │   └── Wav.kt                  reads synthesised speech
│   ├── device/                     Command -> intents / CameraManager / AudioManager
│   ├── weather/                    Open-Meteo forecast + geocoding
│   └── wiki/                       Wikipedia search + summary
└── com/fastsearch/                 The FastSearch search bar, extended
```

## Privacy

- Requests are parsed on the device. Alarms, timers, the torch, volume, media, conversions,
  clocks, apps, contacts and calculations never leave the phone.
- The wake word is heard by an offline model on the phone. The only network use is the one-time
  model download from alphacephei.com.
- Weather sends the city name or your location **rounded to about 1 km** to Open-Meteo. "What
  is…" questions send the topic to Wikipedia. Both can be turned off.
- Asking Spotify for an artist, song or album sends that name to Wikidata once, to find its
  Spotify page; the answer is kept on the phone. Saved playlists never leave it.
- The optional Spotify connection talks only to the Spotify app on the phone, using the user's
  own Client ID; Cog itself sends nothing to Spotify's servers.
- The optional "press play in Spotify" helper is an accessibility service limited to Spotify. It
  looks at Spotify's screen only for a few seconds after you ask to play something, taps one
  button, and stores or sends nothing.
- Web suggestions and instant answers send what you type to DuckDuckGo, as in FastSearch.
- Voice requests use the system recognizer, preferring offline recognition. Replies are spoken
  on the device: the Cog voice model, or the phone's offline text-to-speech.
- Permissions are requested only when a feature needs them: contacts, coarse location,
  microphone, notifications (the wake word's status), display over other apps (the wake word),
  notification access (media control). Calls open the dialer pre-filled, so there's no
  `CALL_PHONE` permission.

## Limitations

- **Wi-Fi, Bluetooth, mobile data and airplane mode can't be toggled by a normal app** since
  Android 10. Those commands open the right panel instead; Shizuku/ADB is the natural next step.
- The wake word's offline model is English, so the wake phrase must be English words. Greek
  commands after it are recognised by the phone's recognizer, which needs Greek installed for
  offline use.
- The wake word costs some battery and keeps the mic indicator on while it listens.
- Music needs the app installed and signed in. Without media control, each music app decides
  whether "search and play" starts playing or only shows results.
- The flashlight stays on while the app's process is alive.
- The rest of FastSearch's limitations apply: the assistant role is user-selected, and some
  Samsung side keys only launch Bixby or Gemini.
