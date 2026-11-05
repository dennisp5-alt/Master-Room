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
