# Film Library → LUT On-Demand

**Tanggal:** 2026-11-07
**Commit:** `466cadfc8` — feat: Film Library entries become usable LUTs on demand

---

## Context

Film Library (Toolbox) = katalog ensiklopedia 58 film (`assets/films.json`),
isi spec saja (type/ISO/grain/contrast/manufacturer + contoh foto) — tak bisa
apply ke kamera. Fitur ini membuat tiap entri **usable**: tombol di
FilmDetailScreen menghasilkan LUT parametrik dan menginstalnya sebagai custom
LUT kategori **"Film Library"**, langsung aktif.

## Perubahan

### 1. `lut/film/FilmLutFactory.kt` (baru)
Port pipeline `scripts/generate_film_luts.py` ke Kotlin:
- `filmToLutConfig(film, size=33)` → `LutConfig` UINT16/SRGB 33³.
- `resolveFilmLook`: heuristic metadata → parameter:
  - `type`: B&W → mono (red-weighted luminance); Slide → sat+kontras tinggi;
    Negative → lift + sat sedang; Cinema → shadow teal.
  - `contrast` label (Low…Very High) → skala kontras (pivot 0.45).
  - Keyword nama/manufacturer: Portra warm skin, Gold warm cast, Ektar
    saturated, Velvia punch, Provia/Astia clean, Ektachrome slide,
    Superia/Natura emerald-green band, Cinestill/Vision tungsten, mono
    untuk Tri-X/HP5/Acros/T-Max.
  - `grain` coarse/medium → +4% kontras mid (proxy; grain asli tak bisa di LUT).
- Pipeline sama dgn generator Python: tone curve per channel (black lift,
  white shoulder, contrast, gamma), saturasi luminance-aware, hue-band shift
  (HLS), split toning shadow/highlight.

### 2. `CameraViewModel.createFilmLutFromFilm(film): String?` (suspend)
- Dedupe: custom LUT kategori "Film Library" dgn nama sama → pakai id lama.
- Baru: generate `LutConfig` → `LutConverter.exportToPlut` (in-memory) →
  `CustomImportManager.importPresetLut(bytes, nameMap en/zh, category)`
  (validasi PLUT + tulis `custom_luts/<id>.plut` + `custom_luts.json`).
- `contentRepository.refreshCustomContent()` sinkron di IO → lalu `setLut(id)`
  di main — menghindari race refresh-custom-content async vs loadLut.

### 3. `FilmDetailScreen.kt`
Param `onCreateLut: ((Film) -> Unit)?`; tombol hitam full-width
**"Buat LUT dari film ini"** di antara Specs dan Examples.

### 4. `MainActivity.kt` (route FILM_DETAIL)
Callback → coroutine → Toast sukses (`film_create_lut_success` dgn nama) /
gagal.

### 5. Strings
`film_create_lut`, `film_create_lut_success`, `film_create_lut_failed` di
`values`, `values-in`, `values-zh-rCN`.

## Dampak

- Semua 58 entri Film Library kini bisa jadi LUT aplikatif (on-demand,
  picker tetap bersih).
- LUT hasil masuk alur existing penuh: picker kamera, LUT Management
  (preview grid, rename, delete, export, reorder).

## Risiko & batasan

- Grain/ISO tidak bisa diekspresikan LUT 3D — hanya memengaruhi pemilihan
  parameter; look = interpretasi parametrik, bukan replika spektral
  (Spektrafilm/Fotufilm tetap jalur RAW engine terpisah).
- Dedupe by nama — ubah nama LUT → klik ulang film membuat entri baru.
- Build `assembleDefaultRelease` sukses. **Uji perangkat tertunda** — device
  terputus saat install; instalasi & verifikasi UI menunggu USB.
