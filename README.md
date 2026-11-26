# Master Room (trial)

A separate experiment from Stem Lab. It shares no code with it. Stem Lab starts from the
mix and proves each change does no harm. Master Room starts from what a mastering studio does,
makes its decisions once, and leaves the final call to level-matched listening.

## The path

| Studio step | Here |
|---|---|
| Restoration first | `Repair`: isolated clicks, short dropouts, a stray burst before the song |
| Listen to the whole song first | `Listen`: BS.1770 loudness, true peak, mid and side tone in third octaves |
| Compare with reference records | `Target`: built-in starting curve, or measured from records you load (numbers only) |
| A few broad EQ moves, mid and side | `Eq`, `FirFilter`: four moves, 500 Hz-1 kHz left alone, linear phase |
| Centre the deep bass | Side low-cut below 100 Hz when the song needs it |
| Corrective dynamics | `DynamicBand`: harshness, sibilance, kick and bass peaks, each only when it jumps out |
| Ride the fader | `Rides`: only for songs that swing too far |
| Variable-mu bus compressor | `VariMu`: ratio rises with level, bass kept out of the detector |
| Transformer, tape, valve | `Tone`: at 4x, at a calibrated 0 VU = -18 dBFS |
| Check the print | `Eq.missing`: measures what the EQ really did and corrects it, then checks the low end after the chain |
| Clip, then limit | `Stack`: 4x soft clipper takes sudden peaks only, held notes go to a true-peak limiter |
| Noise | `Noise`: listens to the song's quiet moments for hiss, whine and hum that never change, and eases them only while the music is close to them |
| Clean-up | `CleanUp`: turns down narrow resonances only while they ring, and eases top-end specks that stand alone in time. Can be switched off |
| Sections | `Sections`: finds quieter and bigger parts of the song, corrects each slightly towards the whole, and lifts the centre if it loses ground in the big parts |
| Density | `Density`: on loud masters, eases each band's loudest moments so the limiter does less |
| Check before delivery | `Distortion`: compares audio before and after the loudness stage, judges what was added against what the music hides, then eases clipping or level until it is clean |
| Streaming check | `Streaming`: encodes the loudest 12 seconds to MP3 and AAC, decodes them and measures the true peak that comes back |
| Dither and deliver | `Wav`, `Resample`, `Release`: 24-bit at source rate, 16-bit 44.1 kHz, tagged 320 kbps MP3 |
| Decide by ear | `AbPlayer`: level-matched, gapless, optional blind test, imports a Stem Lab master |

Three takes: Clean, Warm, Punch. -14 LUFS uses a -1 dBTP ceiling; louder targets use -2 dBTP.

## Checks

`tools/check/.../EngineChecks.java` covers loudness, true peak, timing, the limiter ceiling,
resampling and awkward files. `tools/measure.py` re-measures masters with ffmpeg.
`tools/make_song.py` makes a synthetic test song, which is useful for measuring and useless for
judging sound.

Tuning still to be done by ear: the built-in tone curve, colour amounts, take settings, and how
much of the peak work the clipper should take (set on Brand New Boots: up to 2 dB of the ordinary
peak level, 2.5 dB on Punch).

## Windows app (trial)

`desktop/` builds Master Room for Windows from the same engine source as the phone (`app/src/main/java/.../engine`
and the jump3r MP3 code), compiled as plain Java; nothing in `app/` changes.

- **Build 1:** open or drop a song (WAV and MP3 in Java; FLAC, M4A, AAC, OGG, Opus, AIFF, WMA through the bundled
  FFmpeg), pick style and loudness, master Clean, Warm and Punch, and see loudness, true peak, the check and the
  streaming check (MP3 320, MP3 128 and AAC 256, as on the phone). Takes, MP3s and the session sheet go to
  `Documents\Master Room\<song>\<date time>\` (or the folder chosen with Change). Command line: `"Master Room.exe"` is the window;
  `"Master Room CLI.exe" --master song.wav [--style ROCK] [--level -14] [--out folder]` runs headless.
- **Same sound as the phone:** `desktop/check/DesktopCheck.java` masters a test song through the app and through
  the engine directly and requires byte-identical 24-bit and 16-bit files, plus loudness, ceilings, MP3, FLAC and AAC.
- **CI:** `.github/workflows/build-windows.yml` compiles and checks on Windows, packages a portable app with Java
  and FFmpeg included (`MasterRoom-Windows-<version>.zip`, and an MSI when WiX is available) and screenshots the window.
  The app is not code-signed: Windows SmartScreen asks once (More info → Run anyway).
- **Build 2, listening:** after a round, play the Original and the three takes against each other. Switching keeps
  your place and crossfades over one 2048-sample block; level match (on by default, playback only) uses the phone's
  rule, so every version plays at the masters' level and the original comes up only as far as its peak allows. The
  PLAYS AT column shows each trim. Keys: Space play/pause, 1-4 versions, M level match, arrows 5 s, Home to start.
  Blind test as on the phone: shuffled, names hidden, matched; pick, then see which it was. Picks are added to
  `Documents\Master Room\blind test picks.txt`. `PlayerCheck` checks matching, crossfades and exact playback.
- **Build 2.1:** rounds go to the Documents folder Windows actually uses (it can be moved to OneDrive or another
  drive), and **Change** next to Master picks any other folder (remembered).
- **Build 3, console:** a column beside the results with the phone's loudness arc (gold target mark; at rest the
  selected version's measured loudness, playing: momentary loudness of what you hear), integrated / true peak / range
  readings, and the LIGHTS (live spectrum) and TONE (original and take against the style's target) views. The
  waveform follows the selected version. Everything is measured from the audio being heard, lined up with the sound
  card's position; numbers stay hidden in the blind test. Blind picks are also kept in the phone's taste format
  (`taste.txt`) for a Yours take later.
- **Build 4, stems:** **Open stems** (or drop the WAVs, or Stem Studio's ZIP); each file is placed by the stem word in
  its name. The STEM BALANCE view shows the phone's auto balance in words, with solo (listening only), mute (also in
  the master) and 0.5 dB nudges per stem; Back to auto returns to the app's balance. Play hears the balance live
  through the console. Master renders exactly as the phone does: the stems summed as they came are the source, then
  vocals and other instruments are treated on their own, the stems balanced (with the vocal rides) and mastered.
  `StemCheck` requires the takes to be byte-identical to the phone's engine sequence. CLI:
  `"Master Room CLI.exe" --master --stems <WAVs or ZIP>`.
- **Build 4.1, Suno stems:** Suno's download (numbered files such as `7 Vocals.wav`, `6 Backing_Vocals.wav`, extra
  stems, the full mix as `0 Song Name.wav`, 32-bit float, ZIP sizes only in the central directory) now loads: the
  full mix is left out and gives the title; Vocals + Backing Vocals, Drums + Percussion, and FX, Synth, Sample,
  Strings and the like into Other are added sample for sample into one float WAV each; Keyboard goes with Piano;
  a core stem a set lacks is stood in for by silence (never given a level). All of it is listed on the session sheet.
- **Build 4.2:** Suno downloads that start with a stem (`0 Lead Vocals.wav`, no full mix) keep it: only a number-0 file
  that names no stem is taken for the full mix. Numbered sets take the title from the ZIP or folder name.
- **Build 4.3, every file its own row:** Stem Studio's 4 to 6 stems and Suno's up to 12 each get a row with solo, mute
  and a level (more than six in two columns), and the live player plays each file on its own. The auto balance still
  decides per engine stem (Vocals, Drums, Bass, Other…) and every file of that stem shows the level it got. Set by
  hand, a stem made of several files is the unmuted files added at their own levels; the sheet lists them.
- **Build 5, references, album and saving (from the phone):** TONE FROM on the console shows what sets the tone;
  **Records…** keeps 3 to 5 reference records per style (only their measured numbers) and runs album match. Album
  first, then records, then the style's built-in curve, as on the phone; an album with saved songs also sets the
  loudness, and the session sheet checks each take against it. **Save take** writes the selected take as tagged
  release files (24-bit and 16-bit WAV with LIST INFO, MP3 320 with ID3, plus the sheet) into `Saved`, adds it to the
  album and counts it for the taste. After 8 picks a **Yours** take (blend of your picks) joins every round. With
  records or an album, the console compares the selected take on punch, low end and width, and names the closest.
  The meter shows the ceiling. Kept in `%APPDATA%\Master Room` in the phone's file formats.
- **Build 6, picks up where you left off:** the app opens on the last round (takes ready to play) and on the stems
  that were open, with a by-hand balance and mutes as they were left; the round is kept as `round.properties` in its
  folder (numbers and file paths only), and anything moved or deleted is simply left out. Mastering the same song
  or stems again keeps the round before beside the new one (marked EARLIER, with its settings, e.g. "Rock -14"), as
  on the phone. **+ COMPARE A MASTER** brings in a master made elsewhere (Stem Lab, another tool), converted to the
  song's sample rate if needed, measured like the takes and played level-matched with them. The app has its own
  icon (the console's loudness arc), on the window, the program and the installer.
- **Build 7 (0.7.0), Vocal Tune:** gentle automatic pitch correction for a voice on its own (`engine/VocalTune.java`, shared with the
  phone engine but used only by the Windows app for now; the phone's results are unchanged).
  - **Where:** VOCAL TUNE next to MP3 320 (Off, Natural, Firm, Hard tune) applies to the vocal stem when mastering stems, before its
    clean-up. **Tune vocal** (next to Open stems) tunes one file on its own: a vocal stem or a dry vocal WAV (any format the app
    reads), writes `<name> - tuned (<setting>).wav` as 32-bit float with a short report in `<save folder>\<name>\Vocal Tune <date time>\`,
    and opens Original against Tuned on their own level-matched player (switching keeps your place).
  - **How:** follows the pitch (YIN), averages vibrato out to find each note's centre, and nudges a note towards the nearest
    semitone only when it is more than the dead band out (Natural 12 cents, 60% of the way, 70 ms; Firm 8 cents, 85%, 35 ms),
    so blue notes and slides are never moved to another note and vibrato stays. Hard tune snaps every moment to the key's
    notes, as an effect. Pitch-synchronous overlap-add moves the pitch and keeps the formants; left and right get the same move.
  - **Leaves alone:** singing already in tune (the same samples come back), stretches where more than one voice sings
    (harmonies, doubles), and anything with too much besides one voice in it (a full mix, heavy bleed). The report says
    which, and the result is measured again afterwards: notes nudged, typical move, share of sung time within 20 cents
    before and after, and the key.
  - CLI: `"Master Room CLI.exe" --tune vocal.wav [--vocal-tune natural|firm|effect] [--out folder]`, and `--vocal-tune` with `--master --stems`.
  - `desktop/check/VocalTuneCheck.java` checks it on synthetic singing with known detuning: notes pulled in without passing the note,
    vibrato and formants kept, in-tune singing untouched, harmonies and a full mix left alone, stereo balance kept, 48 kHz.

