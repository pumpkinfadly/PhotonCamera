# Release Fix + Preview Mode LUT Management

**Tanggal:** 2026-11-06
**Commit:**

| Hash | Judul |
|---|---|
| `9a45df8c7` | fix(release): restore compose BOM, Gson keep rules, debug-sign fallback |
| `a9f7ee546` | feat: preview grid mode in LUT management |

---

## 1. Perbaikan build release (`9a45df8c7`)

### Latar masalah
Percobaan `assembleDefaultRelease` pertama gagal di resolusi dependensi:
`Could not find androidx.compose.material3:material3:.` (versi kosong), untuk
semua flavor/buildType — termasuk `devDebug` yang sebelumnya sukses.

### Penyebab
`implementation(platform(libs.androidx.compose.bom))` terhapus dari
`app/build.gradle.kts` oleh quick-fix IDE atas warning *"Dependency declared
multiple times"* — warning itu false positive: BOM dideklarasikan dua kali
dengan sengaja (`implementation` + `androidTestImplementation`, konfigurasi
berbeda). Tanpa BOM, semua artifact Compose di catalog tanpa versi eksplisit
(`androidx-ui`, `material3`, dst.) teresolve dengan versi kosong.

### Perubahan
1. Restore `implementation(platform(libs.androidx.compose.bom))`.
2. Proguard: keep `-keepattributes Signature` + `TypeToken` + model `Film` —
   `FilmData` mem-parse `assets/films.json` reflektif via Gson
   `TypeToken<List<Film>>`; tanpa keep, R8 full mode melepas generik + rename
   field → daftar film diam-diam kosong di release (crash halus, tak terlihat
   di build).
3. `release.signingConfig` fallback ke debug keystore bila properti
   `RELEASE_*` tidak diset — build pribadi bisa langsung diinstall.

### Dampak & verifikasi
- `assembleDefaultRelease` sukses: 62.4 MB APK, R8 + native Release.
- Signature V2 (debug cert), install + smoke test di device sukses
  (proses hidup, crash buffer bersih).

---

## 2. Fitur: preview grid mode di LUT Management (`a9f7ee546`)

### Latar masalah
FilterManagementScreen list-only: tiap baris hanya nama + chips. Pengguna tak
bisa melihat efek LUT sebelum memilihnya sebagai default.

### Perubahan
- **Toggle list/preview** di TopAppBar (`AppIcons.GridView` ↔
  `Icons.AutoMirrored.Filled.List`), state `rememberSaveable`, hanya tampil
  di luar mode multi-select.
- **Mode preview** = `LazyVerticalGrid` 3 kolom dari `filteredLutList` yang
  sama — tab kategori, locate-on-open (`locateLutId`), dan semua dialog tetap
  berfungsi.
- **Kartu `LutPreviewCard`**: `AsyncImage` + Coil `Transformation` — pola
  identik `LutSelector.LutItem` (ui/components/LutSelector.kt:702-741):
  load `lutConfig` + `loadColorRecipeParams` di IO dalam transform, apply via
  `contentRepository.imageProcessor.applyLut` (instance shared, Coil memory
  cache dengan `cacheKey` per sampel+LUT id).
- **Sumber sampel**: foto terbaru non-video dari `GalleryViewModel.latestPhoto`
  (di-wire dari MainActivity), di-load `GalleryManager.loadBitmap(maxEdge=720)`.
  Fallback: bitmap sintetis programatik (band skin-tone/sky/foliage/shadow)
  bila galeri kosong — mode tetap berguna tanpa akses foto.
- **Overlay kartu**: nama di scrim bawah, bintang favorit, chip VIP, border
  aksen + nama berwarna untuk default aktif. Tap kartu = set default
  (`setPhotoLut`/`setVideoLut` sesuai mode screen).
- Multi-select memaksa kembali ke list mode.
- String baru di 3 locale: `values` (en), `values-in`, `values-zh-rCN`.

### Dampak
- Perbandingan visual LUT langsung di halaman manajemen — tak perlu
  pilih-pilih lewat picker kamera satu per satu.
- Render card async di thread Coil/GL `LutImageProcessor` — main thread aman;
  hasil di-cache Coil memory (tidak render ulang saat scroll).

### Risiko & batasan
- Grid tidak mendukung drag-reorder (reorder tetap di list mode).
- Kartu tidak membuka menu aksi (rename/copy/export/delete) — semua aksi
  tetap di list mode; tap kartu langsung set default.
- Recipe target preview = null (photo target), konsisten dengan pemakaian
  LutSelector.
- Verifikasi: compile dev debug + assembleDefaultRelease sukses. Uji manual
  di device tertunda — device terputus saat install; APK siap di
  `app/build/outputs/apk/default/release/app-default-release.apk`.
