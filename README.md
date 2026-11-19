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
- Next builds: references and settings, polish and an icon.
