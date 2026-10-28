# Tinnitus Relief (native Android)

Kotlin · Jetpack Compose · Media3 · AudioTrack · DataStore. Radi potpuno offline, bez naloga, reklama i interneta (aplikacija nema INTERNET dozvolu).

## Faza 1 (ovaj projekat)

- Glavni ekran: izabrani zvuk, veliko dugme Pusti/Pauza, jačina, tajmer za spavanje, „Zvuci", ulaz u Akustičnu laboratoriju.
- Zvuci: Tuš (ugrađen snimak, `res/raw/shower.ogg`), beli, ružičasti i braon šum (generišu se uživo), i tvoji uvezeni fajlovi.
- Zamena zvuka tuša svojim snimkom: Podešavanja → Zvuk tuša.
- Puštanje u pozadini: foreground servis + MediaSession, kontrole na zaključanom ekranu i u notifikaciji, audio focus (pauza za poziv, nastavak posle), pauza kad se izvuku slušalice.
- Bezbednost zvuka: konzervativna početna jačina (−26 dB), fade-in/fade-out, glatki prelazi između zvukova, peak limiter na −1 dBFS.
- Akustična laboratorija je u ovoj fazi samo najava; sloj B (akustična stimulacija) postoji u motoru, ali je isključen.

## Kako dobiti APK

Ovaj projekat nije Expo/React Native projekat i ne može da se izgradi preko Expo-a.

### Opcija A: GitHub (bez instaliranja Android alata)

```cmd
cd TinnitusRelief
git init
git add .
git commit -m "Tinnitus Relief - faza 1"
git branch -M main
git remote add origin https://github.com/TVOJE_IME/tinnitus-relief.git
git push -u origin main
```

Na GitHub-u otvori **Actions → Android build → poslednji run → Artifacts → TinnitusRelief-debug-apk**. Skini zip, unutra je `app-debug.apk`. Prebaci ga na telefon i otvori (dozvoli „instalaciju iz nepoznatih izvora").

### Opcija B: Android Studio

File → Open → izaberi folder `TinnitusRelief` → sačekaj Gradle sync → priključi telefon (USB debugging) → Run ▶.
Ili iz komandne linije: `gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Potpisan release AAB za Google Play

1. Napravi ključ (čuvaj ga zauvek, bez njega ne možeš da objaviš update):
   ```cmd
   keytool -genkeypair -v -keystore release.jks -alias relief -keyalg RSA -keysize 4096 -validity 10000
   ```
2. U korenu projekta napravi `keystore.properties` (u .gitignore je, ne commituj ga):
   ```properties
   storeFile=release.jks
   storePassword=...
   keyAlias=relief
   keyPassword=...
   ```
3. `gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`
4. U Play Console uključi Play App Signing i otpremi AAB.
5. Pre objave promeni `applicationId` u `app/build.gradle.kts` na svoj jedinstveni (npr. `rs.tvojeime.tinnitus`) i po želji ime aplikacije u `res/values/strings.xml`. Za Play deklaraciju foreground servisa izaberi „Media playback".

## Testovi

`gradlew testDebugUnitTest` pokreće JVM testove DSP jezgra (`MixerCoreTest`): tačnost pojačanja, taper jačine, limiter (nikad ne prelazi plafon), fade bez klikova, crossfade pri promeni zvuka, ducking, sloj B je tih dok se izričito ne uključi, kontinuitet petlje, stabilnost i nivo šuma. Svih 10 prolazi.

## Struktura

```
audio/MixerCore.kt     čisto DSP jezgro: sloj A (masker), sloj B (stimulacija), fade, duck, limiter
audio/AudioEngine.kt   AudioTrack (float PCM) + real-time nit
audio/Voices.kt        petlja PCM-a sa resamplingom, generatori šuma
audio/PcmDecoder.kt    dekodiranje bilo kog formata (MediaCodec) + bešavna petlja
playback/              Media3 SimpleBasePlayer, foreground MediaSessionService, audio focus
data/                  biblioteka zvukova (privatni storage), DataStore podešavanja
ui/                    Compose ekrani
tools/generate_shower.py  skripta kojom je sintetisan ugrađeni zvuk tuša
```

Aplikacija ne postavlja dijagnozu, ne leči i ne izleči tinitus.
