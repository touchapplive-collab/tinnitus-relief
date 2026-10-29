# Tinnitus Relief — native Android (Kotlin + Jetpack Compose)

Potpuno offline aplikacija za ublažavanje tinitusa zvukom, sa odvojenom eksperimentalnom akustičnom laboratorijom.

- Nema naloga, reklama, pretplata ni analitike.
- Nema dozvole za internet.
- Svi podaci ostaju na telefonu.

> **Status builda:** APK se uspešno gradi na GitHub Actions; poslednji build je na stalnom linku ispod. 63 unit testa prolaze lokalno i na GitHub-u.

## Funkcije

### Relief (glavni ekran)
- Veliko dugme Pusti/Pauza, glavna jačina, tajmer za spavanje (15/30/60/90 min, sa blagim utišavanjem od 8 s) i dugme Zvuci.
- **Mikser:** do 3 zvuka istovremeno, svaki sa svojom jačinom.
- **Sačuvani miksevi:** imenovani preseti.
- **Zvuci** (svi offline):
  - tuš;
  - blaga kiša, jaka kiša, okean, reka, vodopad, ventilator (sintetisani, bešavne petlje);
  - beli, roze i braon šum (generišu se uživo).
- **Uvoz:** tvoji audio fajlovi (MP3/M4A/OGG/FLAC/WAV) kopiraju se u privatni storage.
- **Zamena tuša** tvojim snimkom: Podešavanja → Zvuk tuša.
- **Pozadinsko puštanje:** foreground servis (Media3 MediaSession).
  - Kontrole su na zaključanom ekranu, u notifikaciji i na Bluetooth dugmadi.
  - Audio focus: pauza za poziv i automatski nastavak posle njega; utišavanje za navigaciju.
  - Pauza kad se izvuku slušalice.
  - Wake lock postoji samo dok zvuk svira.

### Personalizacija zvuka („Pronađi svoj savršeni zvuk")
- Relief → **Personalizuj zvuk**: radi sa svakim zvukom (tuš, priroda, šumovi, tvoji fajlovi).
- FFT analiza na telefonu pronalazi u kojim delovima spektra zvuk zaista ima energije; samo ti delovi se menjaju.
- 5–8 poređenja „Zvuk A / Zvuk B" (Bolji je A / Bolji je B / Nema razlike), uz Nazad, Preskoči i nastavak kasnije.
- Obe verzije su izjednačene po glasnoći (A-ponderisano), pa se poredi boja zvuka, a ne jačina. Prelazi su glatki (150 ms).
- Široki, blagi ekvilajzer u 4 oblasti, ograničen na −8…+6 dB. Bez jasne sklonosti aplikacija preporučuje original.
- Rezultat („Moj tuš") se čuva kao podešavanje, bez kopije audio fajla; original ostaje netaknut. Na Relief ekranu: Original | Moj tuš.
- Laboratorija → Testovi → **Original ili personalizovano**: slepe sesije uravnoteženim nasumičnim redom, ocene pre / dok svira / posle / prijatnost.

### Acoustic Lab (eksperimentalno, podrazumevano isključeno)
- **Uključivanje:** zahteva izričitu potvrdu.
- **Visina tinitusa:**
  - vođeni 3AFC postupak iz objavljenog rada, sa proverom oktave i finim podešavanjem;
  - ručni logaritamski tjuner;
  - opciono 3AFC određivanje glasnoće.
- **Neural Acoustic Engine:** modularni DSP sa zero-phase STFT obradom i generatorima. Režimi:
  - inspirisano DFCRS-om;
  - sa zarezom (notched);
  - HFEM (samo za poređenje);
  - prilagođeni filter;
  - uskopojasni šum;
  - čist, AM i FM ton.
- **Prikaz:** grafik frekvencijskog odziva i oznaka šta je objavljeno, a šta inženjerska pretpostavka.
- **Sloj B (stimulacija):**
  - nezavisna jačina, ograničena na −6 dB;
  - može da svira sam ili zajedno sa maskerom;
  - sesije traju 1–60 min (podrazumevano 15), sa fade-in i fade-out od 3 s;
  - nikad se ne nastavlja sam posle prekida.
- **Testovi i praćenje:**
  - test rezidualne inhibicije;
  - test olakšanja (pre / odmah posle / 5 min kasnije);
  - dnevna ocena **bez zvuka** (aplikacija upozori i ponudi pauzu ako nešto svira), uz neobaveznu ocenu koliko smeta;
  - pouzdanost visine tinitusa („Koliko si siguran?" posle svakog određivanja; niska / srednja / visoka);
  - nedeljni napredak: maskiranje, prijatnost, rezidualna inhibicija i dugoročna promena prikazani odvojeno; trend tek posle 4 nedelje;
  - izvoz cele istorije u CSV i kopiranje podataka.

Detalji o tome šta je naučno potvrđeno: **[docs/RESEARCH.md](docs/RESEARCH.md)**.

## Bezbednost zvuka
- Peak limiter na −1 dBFS i hard-clip zaštita na izlazu (testirano: izlaz nikad ne prelazi plafon).
- Sve promene jačine i zvuka su izglađene, bez klikova.
- Konzervativna početna jačina (−26 dB).
- Lab tonovi su ograničeni na −12 dBFS.
- Softverski limiter ne garantuje bezbedan nivo zvučnog pritiska na svim slušalicama, i aplikacija to jasno kaže.

## Kako dobiti APK (GitHub, bez instaliranja alata)

1. Na github.com napravi **prazan javni** repozitorijum, npr. `tinnitus-relief`.
2. U folderu projekta pokreni:
   ```cmd
   git init
   git add .
   git commit -m "Tinnitus Relief"
   git branch -M main
   git remote add origin https://github.com/TVOJE_IME/tinnitus-relief.git
   git push -u origin main
   ```
3. Na GitHub-u otvori **Actions** i sačekaj zeleni kružić (oko 5–8 min).
4. APK je na stalnom linku:
   `https://github.com/TVOJE_IME/tinnitus-relief/releases/latest/download/TinnitusRelief.apk`

**Instalacija na Samsung:**
1. Otvori link u Chrome-u ili Samsung Internetu na telefonu.
2. Otvori preuzeti fajl i dozvoli „Instaliranje nepoznatih aplikacija" za taj pregledač.
3. Ako se pojavi Play Protect upozorenje, izaberi „Ipak instaliraj".
4. Svaki novi build ima isti potpis, pa se instalira preko starog bez gubitka podataka.

**Ako build ne prođe:** u Actions otvori crveni run → *Build debug APK* i kopiraj poruku greške.

**Android Studio (alternativa):** File → Open → folder projekta → Run ▶, ili iz komandne linije `gradlew assembleDebug`.

## Release AAB za Google Play
1. Napravi ključ i čuvaj ga zauvek:
   ```cmd
   keytool -genkeypair -v -keystore release.jks -alias relief -keyalg RSA -keysize 4096 -validity 10000
   ```
2. U korenu projekta napravi `keystore.properties` (u .gitignore je):
   ```
   storeFile=release.jks
   storePassword=…
   keyAlias=relief
   keyPassword=…
   ```
3. Pokreni `gradlew bundleRelease`. Rezultat je `app/build/outputs/bundle/release/app-release.aab`.
4. Pre objave:
   - promeni `applicationId` na svoj jedinstveni;
   - u Play Console izaberi Play App Signing i deklaraciju foreground servisa „Media playback";
   - zdravstvena aplikacija mora imati jasnu izjavu da ne postavlja dijagnozu i ne leči.

## Testovi
- **Lokalno (već pokrenuto):** 63 testa, svi prolaze. Rezultati su u [docs/test-results.txt](docs/test-results.txt).
- **Na GitHub-u:** `gradlew testDebugUnitTest` se pokreće pre svakog builda.

| Oblast | Šta se proverava |
|---|---|
| FFT | poređenje sa direktnim DFT-om, povratna transformacija |
| Opsezi | DFCRS jednačine iz rada, NBN 1/3 oktave, zarez od 1 oktave, HFEM 6–9 kHz |
| Spektralna obrada | transparentnost (greška < 1e-4); tačno +10 dB u opsezima i 0 dB van njih; tačnost širine na ivicama; dubina zareza > 40 dB; promena opsega bez klikova |
| Filteri | RBJ band-pass, peak, low-pass i notch odziv |
| Generatori | tačnost frekvencije, granice AM/FM, kalibracija i selektivnost uskopojasnog šuma, tajming 3AFC tonova |
| 3AFC | objavljeno pravilo sužavanja (2520–6350 Hz u primeru iz rada), konvergencija za 6 visina, glasnoća do 1 dB |
| Mikser | tačnost pojačanja, limiter (nikad ne seče), fade i crossfade bez skokova, ducking, najviše 3 kanala, ograničenje sloja B, sloj B tih dok se ne uključi, 3 minuta mešanja bez NaN-a i preko plafona |
| Petlje | bešavan prelaz petlje i normalizacija; svih 7 ugrađenih OGG fajlova proveren alatom `tools/check_loops.py` |
| Prekidi | audio focus: poziv → pauza → automatski nastavak; navigacija → utišavanje; druga muzika → pauza bez nastavka; korisnik pauzirao za vreme poziva → nema nastavka |
| Personalizacija | FFT tačnost na poznatim sinusima, boje šuma (beli +3 dB/okt, roze ravan, braon opada), analiza ne pretpostavlja energiju svuda, odziv filtera, izjednačenje glasnoće (±0,05 dB), EQ u realnom vremenu, promena bez klika, 5–8 poređenja, kretanje ka preferenci simuliranog slušaoca, granice pojačanja, „nema razlike" → original, nazad/preskoči/determinizam, nastavak sesije, A/B prelaz bez klika i automatsko zaustavljanje |
| Vođeno određivanje | tonovi do 5 s, ulaz/izlaz 150 ms, trenutno prebacivanje bez klika, ponavljanje, brzo dodirivanje, kompenzacija glasnoće samo utišava |
| Praćenje | pravila pouzdanosti visine, nedelje od ponedeljka, trend tek sa dovoljno podataka, jedan dan sa 0 nije nedelja bez tinitusa, CSV sa ispravnim navodnicima, stari zapisi se i dalje čitaju |
| Podaci | zapis/čitanje odgovora, oštećeni redovi se preskaču, kodovanje miksa i preseta, odbijanje neispravnih vrednosti podešavanja |

**Šta nije testirano (traži pravi telefon):**
- rad u pozadini na Samsung One UI;
- Bluetooth i žičane slušalice;
- potrošnja baterije u dužim sesijama;
- izgled interfejsa;
- ponašanje MediaSession-a.

## Struktura
```
dsp/        čisto Kotlin DSP (bez Androida, testirano): FFT, Biquad, SpectralShaper, opsezi iz radova,
            generatori, 3AFC, sesije, audio-focus politika, kodeci
audio/      MixerCore (slojevi A i B, limiter), AudioEngine (AudioTrack nit), dekoder, bešavne petlje
engine/     MaskerEngine, FrequencyMatchingEngine, AcousticProcessingEngine, SessionManager,
            LocalResponseTracker, test rezidualne inhibicije i test olakšanja
playback/   Media3 SimpleBasePlayer + foreground MediaSessionService
data/       biblioteka zvukova, DataStore
ui/         Compose: Relief, Acoustic Lab, Settings
tools/      generisanje zvukova, provera petlji, analiza objavljenih DFCRS audio primera
docs/       RESEARCH.md, test-results.txt
```

Aplikacija ne postavlja dijagnozu, ne leči i ne izleči tinitus.
