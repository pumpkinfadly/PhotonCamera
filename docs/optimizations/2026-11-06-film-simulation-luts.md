# Film Simulation Built-in LUTs

**Tanggal:** 2026-11-06
**Commit:** `4ee46edc9` — feat: six built-in film simulation LUTs, un-VIP all film LUTs

---

## Context

Permintaan: tambah beberapa film simulation built-in. Dari 4 sistem "film" di
repo (Film Library = ensiklopedia tanpa rendering, Spektrafilm = butuh model
spektral upstream, Fotufilm = JSON fisika terukur), target yang dipilih:
**built-in LUT** (`assets/luts/config.json`) — daftar preset picker dengan
kategori "Film" yang sudah ada (Ultramax, E100, Cinestill 800T, dll.).

Karena payload LUT film tidak tersedia untuk diunduh (lisensi/berbayar),
enam LUT baru **digenerate programatik** dari parameter warna per stock.

## Perubahan

### 1. `scripts/generate_film_luts.py` (baru)
- Pure Python stdlib (`colorsys`, `math`) — tanpa numpy. Deterministik.
- Pipeline per titik LUT (33³):
  1. Per-channel tone curve dari params: black lift (fade), white shoulder
     (kompresi highlight), contrast di pivot 0.45, gamma.
  2. Saturation skala luminance-aware (bobot mid lebih tinggi utk stock
     yang punya `mid_saturation` terpisah).
  3. Hue-band shift: rotasi hue + boost sat di rentang tertentu dengan mask
     smoothstep wrap-around (greens→emerald Superia/Velvia, skin→warm Portra).
  4. Split toning: tint shadow/highlight berbobot luminance kuadrat.
  5. Mono (Tri-X): luminance red-weighted (0.34/0.44/0.22 — kulit terang,
     khas pankromatik) lalu tone curve kontras keras.
- 6 stock: Portra 400, Velvia 50, Tri-X 400, Gold 200, Superia 400,
  Provia 100F.
- Output 6 `.cube` (≈970 KB each, header + 35.937 baris) langsung ke
  `app/src/main/assets/luts/`.

### 2. `scripts/validate_film_luts.py` (baru)
Sanity check: header 33, jumlah baris = 35.937, semua nilai 0-1. Semua OK.

### 3. `app/src/main/assets/luts/config.json`
- Append 6 entri kategori `"Film"`, `isVip: false` (id `film_kodak_portra_400`,
  `film_fuji_velvia_50`, `film_kodak_trx_400`, `film_kodak_gold_200`,
  `film_fuji_superia_400`, `film_fuji_provia_100f`).
- **Semua `isVip: true` existing → false** (6 film LUT lama jadi gratis juga,
  permintaan user). Total 39 entri, 0 VIP tersisa.

### 4. Tidak ada perubahan Kotlin
`LutParser.parse` fallback ke `CubeLutParser` untuk file non-PLUT — assets
`.cube` langsung terbaca. VIP flag dibaca dari config; gating UI otomatis
ikut.

## Dampak

- Kamera picker + LUT Management kategori Film: 12 LUT film (6 lama + 6 baru),
  semuanya gratis.
- Ukuran APK +≈1.9 MB (6 cube ter-kompresi).
- Preview grid LUT Management (fitur `a9f7ee546`) langsung menampilkan kartu
  LUT baru — validasi visual cepat.

## Risiko & batasan

- LUT programatik = interpretasi artistik, bukan rekayasa dari data spektral
  film asli. Warna "inspirasi stock", bukan replikasi presisi.
- B&W Tri-X tidak termasuk grain (grain tidak bisa diekspresikan di LUT 3D).
- Regenerasi: `python scripts/generate_film_luts.py`; validasi:
  `python scripts/validate_film_luts.py`.
- Verifikasi build: `assembleDefaultRelease` BUILD SUCCESSFUL, APK 64.2 MB.
  **Uji di device tertunda** — device masih terputus saat commit dibuat;
  install ulang begitu USB tersambung.
