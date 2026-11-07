# Dokumentasi Optimisasi

Folder khusus untuk mendokumentasikan semua perubahan optimisasi pada fork ini
(`personal/optimize` @ [pumpkinfadly/PhotonCamera](https://github.com/pumpkinfadly/PhotonCamera)).

## Konvensi

- Satu dokumen per batch perubahan (bisa berisi beberapa item terkait).
- Nama file: `YYYY-MM-DD-judul-singkat.md`.
- Setiap dokumen wajib memuat:
  1. **Commit terkait** — hash + judul.
  2. **Latar masalah** — kode/loop apa yang lambat, lokasi file:baris.
  3. **Perubahan** — apa yang dilakukan, mengapa pendekatan itu dipilih.
  4. **Dampak** — performa/ukuran/battery/stabilitas, diukur atau estimasi.
  5. **Risiko & batasan** — apa yang diuji, apa yang tidak, kondisi tepi.
  6. **Yang sengaja dilewati** — dan alasannya (utang teknis).

## Indeks

| Tanggal | Dokumen | Commit |
|---|---|---|
| 2026-11-06 | [Fase 1 — runtime, native, runBlocking, build](2026-11-06-optimisasi-fase-1.md) | `a957a4e83` `c3c52a302` `d75932b94` `0f6477f54` |
| 2026-11-06 | [Release fix + fitur preview mode LUT Management](2026-11-06-release-fix-dan-lut-preview.md) | `9a45df8c7` `a9f7ee546` |
| 2026-11-06 | [Film simulation built-in LUTs](2026-11-06-film-simulation-luts.md) | `4ee46edc9` |
| 2026-11-07 | [Bugfix: video freeze saat zoom lintas lensa](2026-11-07-fix-video-zoom-lens-freeze.md) | `0d8ce1ce8` |
| 2026-11-07 | [Fitur: ikon disable-all-effects top bar](2026-11-07-disable-all-effects.md) | `4abbf162d` |
| 2026-11-07 | [Zoom control: label x + chip 0.5x/1x/2x](2026-11-07-zoom-control.md) | `cf9ff1d3b` |
| 2026-11-07 | [Film Library → LUT on-demand](2026-11-07-film-library-lut.md) | `466cadfc8` |

## Kandidat berikutnya (belum dikerjakan)

- NEON kernel YUV→RGB preview (`native-lib.cpp:2267` — branch rotasi/format per pixel).
- Solver exposure: 144 MB `push_back` per pixel + 24x rescan (`raw_legacy_auto_exposure_solver.cpp:520`).
- RAISR scratch pool — 8 alokasi vector per band (`mgc_raisr_driver.cpp:211`).
- JNI direct buffer untuk output HDRNet full-res (`dng_hdrnet_pgtm_jni.cpp:559`).
- Eye-focus preview: fenced PBO + reuse bitmap (`LutRenderer.kt:2752`).
- Refactor selector-flow `CameraState` di `CameraScreen` (171 baca state).
- Migrasi keluar dari `PreferencesProto` internal → bump DataStore 1.0.0 → 1.3.x.
