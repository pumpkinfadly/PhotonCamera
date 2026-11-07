# Zoom Control: Label x-Factor Default + Chip 0.5x/1x/2x

**Tanggal:** 2026-11-07
**Commit:** `cf9ff1d3b` — feat(zoom): x-factor labels by default, quick stops 0.5x/1x/2x

---

## Context

Bar zoom sebelumnya: default label **focal length** (35mm equiv) dan chip
penuh — semua lensa + wide stop 0.9x + custom focal (default 35/50/85/200mm)
→ ~7 chip angka mm. User minta: default tampil **faktor x**, chip dibersihkan
jadi `[0.5x (default ultrawide), 1x, 2x]` — custom focal chips dihilangkan
dari bar (keputusan user; fitur pinch/ruler bebas zoom tetap utuh).

## Perubahan

### 1. Label default → x factor
- `UserPreferences.zoomDisplayMode` default `"FOCAL_LENGTH"` → `"ZOOM_RATIO"`
  (+ pref flag `zoomDisplayModeMigratedV2`).
- **One-shot migrasi** di `CameraViewModel.init`: install lama yang tersimpan
  `FOCAL_LENGTH` dipindah ke `ZOOM_RATIO` sekali, lalu user bebas toggle via
  ikon display-mode di bar (fungsionalitas toggle tak berubah).
- `ZoomDisplayMode.fromPersistedName` fallback `?: FOCAL_LENGTH` → `?: ZOOM_RATIO`.

### 2. Chip set back camera — `allZoomStops`
- Param baru `maxZoomRatio: Float = 1f`.
- Back branch baru:
  - **Ultrawide default stop**: stop terkecil < 1x dari `calculateLensZoomStops`
    (= `displayIntrinsicZoomRatio` ultrawide, mis. 0.5x). Tetap di-filter
    `hiddenFocalLengths` (35mm-equiv ±0.5mm) — user yang menyembunyikan focal
    ultrawide tidak melihat chip ini.
  - **1x** selalu.
  - **2x** bila `maxZoomRatio >= 1.95f` (tanpa lensa tele pun muncul sebagai
    digital zoom; di device dengan range < 2x hilang).
- Custom focal chips + `addDefaultMinimumZoomStop` (0.9x) tidak lagi masuk
  bar belakang.
- Front branch: tak berubah (lens stops + 2f + custom focal).

### 3. Call sites
- `ZoomControlBar.kt` / `ZoomControlBarVerticel.kt` (XPAN): deklarasi
  `minZoom`/`maxZoom` dimajukan sebelum perhitungan stops, pass
  `maxZoomRatio = maxZoom`.
- `handleVolumeZoom` (volume-key zoom): hitung maxZoomRatio inline, set step
  kini 0.5x → 1x → 2x.

### 4. Tetap bekerja apa adanya
- Tap 0.5x = switch lensa ultrawide (chip match `cameraIds`), 1x = main,
  2x tanpa lensa 2x → `findOptimalLens` (existing).
- Pinch / continuous ruler + settlement custom chip + variant badge:
  konsumen fungsi sama, tak diubah.
- `addDefaultMinimumZoomStop` dipertahankan sebagai helper private — masih
  dipakai `getAvailableFocalLengths` (editor focal length di settings).

## Dampak

- Bar bersih: 3 chip angka x (0.5x/1x/2x) — tampilan standar aplikasi kamera.
- Info focal length tetap tersedia: toggle ikon display mode di bar.

## Risiko & batasan

- Chip custom focal user tidak lagi tampil di bar belakang (by design).
  Fungsi custom focal (long-press chip) masih ada tapi hasilnya tak terlihat
  di back — kandidat rev jika ingin ditampilkan setelah 3 chip utama.
- One-shot migrasi menimpa pilihan `FOCAL_LENGTH` yang mungkin disengaja —
  diterima (request eksplisit user; toggle manual tetap ada).
- Verifikasi: compile + `assembleDefaultRelease` BUILD SUCCESSFUL. Uji
  perangkat tertunda — device terputus saat commit.
