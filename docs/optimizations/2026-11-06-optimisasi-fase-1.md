# Fase 1 — Runtime, Native, runBlocking, Build

**Tanggal:** 2026-11-06
**Branch:** `personal/optimize` (fork dari `bjzhou/PhotonCamera` @ `8d85d1746`)
**Commit:**

| Hash | Judul |
|---|---|
| `a957a4e83` | perf: throttle per-frame telemetry state updates to 10Hz |
| `c3c52a302` | perf(native): parallelize serial full-image raw loops with OpenMP |
| `d75932b94` | perf: remove main/camera-thread runBlocking on DataStore and metadata loads |
| `0f6477f54` | build: shrink native exports, catalog hardcoded deps, config cache |

**Verifikasi build:** `:app:assembleDevDebug` BUILD SUCCESSFUL
(APK `app/build/outputs/apk/dev/debug/app-dev-debug.apk`; Kotlin compile + native
link incremental bersih; warning semuanya pre-existing).

---

## 1. Throttle pembaruan state telemetry per frame (`a957a4e83`)

### Latar masalah
`Camera2Controller.onCaptureCompleted` (`app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt:1284`)
dieksekusi 30-60x/detik (sekali per frame preview). Setiap eksekusi membuat salinan
baru `CameraState` — data class 142 field — meski nilai yang berubah hanya telemetry
yang berjitter (`iso`, `shutterSpeed`, `actualAwb*`, `focusDistance`, `physicalAperture`).
Akibat: alokasi objek besar di camera thread setiap frame + emisi StateFlow yang
memicu recomposition layar kamera hampir setiap frame.

### Perubahan
- Nilai baru dihitung ke lokal, dibandingkan dengan state saat ini (semua field
  value type — perbandingan murah). Tidak ada perubahan → tidak ada copy/emit.
- Field diskrit (mode AWB, `canAdjustWhiteBalance`, `supportsCctWhiteBalance`,
  range suhu warna) tetap publish segera saat berubah.
- Field kontinu (ISO, shutter, AWB, jarak fokus, aperture) dibatasi maksimum
  ~10 Hz (`TELEMETRY_UPDATE_INTERVAL_MS = 100`).
- `setZoomRatio` (`Camera2Controller.kt:6320`): dua copy state per event gesture
  → skip copy bila nilai tidak berubah.

### Dampak
- Alokasi + emisi state turun dari 30-60 Hz menjadi maksimum 10 Hz; frame stabil
  (nilai tidak berubah) menghasilkan nol alokasi.
- Recomposition UI kamera (Composable ~127 KB) ikut turun ke ≤10 Hz.
- Baterai: lebih sedikit wake-up composition + GC.

### Risiko & batasan
- Nilai telemetry di UI tertunda maksimal 100 ms — tidak terlihat mata untuk
  readout ISO/shutter/fokus.
- Diverifikasi aman terhadap jalur lain: builder request manual/semi-auto
  (`Camera2Controller.kt:4540`) membaca nilai yang di-set pengguna, BUKAN
  telemetry blok ini (guard `isAutoExposure` + `isIsoAuto`). Loop metering
  semi-otomatis (`calculateAutoMetering`, `:3012`) berjalan di mode AE-OFF
  yang tidak pernah ditulis blok ini — konvergensi metering tak terpengaruh.

---

## 2. Paralelisasi loop full-raw serial dengan OpenMP (`c3c52a302`)

### Latar masalah
Tiga loop memproses buffer raw 12-50 MP secara serial-skalar padahal OpenMP aktif
di translation unit yang sama:

1. `dng_hdrnet_pgtm_jni.cpp:594` — evaluasi HDRNet display-grid per pixel di
   seluruh gambar. Loop tetangga di bawahnya (dehaze `:625`, output `:652`)
   sudah OMP; loop termahal justru tidak.
2. `native-lib.cpp:758` — `applyDngBlackLevelDeltas`: pengurangan `double`
   per pixel atas seluruh area aktif.
3. `native-lib.cpp:848` — `applySupportedDngGainMaps`: sampling bilinear gain
   + koreksi black level per pixel.

### Perubahan
- Ketiganya jadi `#pragma omp parallel for schedule(static)` per baris.
  Baris saling independen: satu tulisan per pixel, semua baca sumber (FC,
  black level, tabel gain, delta) hanya-baca.
- HDRNet: statistik histogram dehaze memakai salinan per-thread lalu digabung
  (hasil gabungan identik dengan versi serial — penjumlahan cacah bin).
  Jalur error nilai non-finite memakai flag atomik (pola yang sudah dipakai
  loop output di file yang sama).
- Per-pixel aritmetika tidak berubah → output bit-identik.

### Dampak
- Kecepatan hampir linear terhadap jumlah core big (4-8x pada SoC flagship)
  untuk tiga tahap pipeline RAW ini: evaluasi HDRNet, koreksi black-level
  delta, dan gain map DNG — semuanya di jalur pemrosesan foto hasil bidikan.
- Waktu tunggu hasil foto (RAW → JPEG/preview) turun terutama di mode
  HDRNet/dehaze.

### Risiko & batasan
- Ditambah `#include <omp.h>` dengan guard `#if defined(_OPENMP)`.
- Kompatibel bila build tanpa OpenMP (fallback serial via `thread_index = 0`).
- Jam error: loop kini selesai penuh sebelum return (dulu langsung return di
  pixel buruk pertama) — bekerja ekstra hanya pada kasus error yang langka.

---

## 3. Penghapusan `runBlocking` di thread utama/kamera (`d75932b94`)

### Latar masalah
- `CameraDiscovery.kt:758-878` — 7 fungsi masing-masing `runBlocking` baca
  DataStore setiap refresh daftar kamera (dipanggil dari camera handler thread
  → startup kamera tertahan; `loadLogicalCameraDiscoveryConfig` bahkan membaca
  DataStore dua kali).
- `GalleryViewModel.kt:2133` — `enterEditMode` blokir main thread menunggu
  baca DB+file metadata saat cache miss (UI ngefrost saat tap tombol edit).
- `GalleryViewModel.kt:1332` — `loadSystemPhotoMetadataForEdit` `runBlocking`
  parse ContentResolver + EXIF.
- `GalleryViewModel.kt:2406` — `snapshotEditRecipe` `runBlocking` baca file
  LUT saat masuk mode edit foto lama.

### Perubahan
- CameraDiscovery: semua baca memakai `UserPreferencesRepository.current` —
  cache proses-level yang di-load eager sejak proses start
  (`PreferencesState`, `SharingStarted.Eagerly`). Tulis migrasi ISZ memakai
  `updateAsync` (scope proses, tidak membatalkan saat pemanggil mati).
  Baca duplikat dihapus.
- `enterEditMode`: load metadata dipindah ke coroutine `viewModelScope` di
  IO; sisa logika diekstrak ke `startEditMode(targetPhoto)` dan dijalankan
  setelah metadata siap. Guard `getCurrentPhoto()?.id != targetPhoto.id`
  mencegah race saat pengguna berpindah foto saat load berjalan.
- `loadSystemPhotoMetadataForEdit` menjadi `suspend`.
- `snapshotEditRecipe`: prefetch resep LUT ke cache `prefetchedLutRecipes`
  saat masuk edit (IO); `runBlocking` tinggal fallback langka untuk jalur
  paste/sync-toggle.

### Dampak
- Startup/refresh kamera: hilang hingga 8 round-trip DataStore blocking
  (puluhan-ratusan ms di cold start) per refresh daftar lensa.
- Tap edit foto: main thread tidak lagi membeku menunggu DB/EXIF — layar
  edit muncul setelah metadata siap (total waktu sama, tanpa jank).

### Risiko & batasan
- `current` mengembalikan default bila load pertama DataStore belum selesai —
  window kecil (load eager dimulai saat proses start, jauh sebelum kamera
  dibuka). Efek terburuk: discovery memakai setelan default untuk sesi itu,
  terkoreksi di refresh berikutnya.
- `isEditing = true` kini ditunda sampai metadata termuat (sebelumnya instan
  setelah blokir) — urutan UI tetap sama dari sudut pandang pengguna.

### Yang sengaja dilewati
- `setEditLut` (`GalleryViewModel.kt:2482`) tetap `runBlocking`: versi async
  berisiko menimpa draf slider pengguna dengan resep yang termuat terlambat.
- `VideoRecorder.kt:244,1420,1481` dan `LutImageProcessor.kt` tetap
  `runBlocking(renderDispatcher)`: start recording punya dependency sekuensial
  encoder→GL→muxer; jalur cleanup/release memang harus blocking.

---

## 4. Build: simbol native, catalog, config cache (`0f6477f54`)

### Perubahan & dampak

| Berkas | Perubahan | Dampak |
|---|---|---|
| `app/src/main/cpp/CMakeLists.txt` | `-fvisibility=hidden -fvisibility-inlines-hidden` + `-ffunction-sections -fdata-sections` + linker `-Wl,--gc-sections` pada `my-native-lib` | Tabel simbol dinamis .so menyusut (ekspor JNI tetap utuh via `JNIEXPORT`); ukuran .so lebih kecil, load lebih cepat |
| `app/build.gradle.kts` (release) | `arguments += "-DCMAKE_BUILD_TYPE=Release"` | Build release tak lagi emit `-g` (dulu RelWithDebInfo) untuk ratusan objek LibRaw/DNG SDK — kompilasi+link release lebih cepat |
| `gradle.properties` | `org.gradle.configuration-cache=true`, `org.gradle.parallel=true`, hapus referensi `docs/build-performance.md` yang tak ada | Fase konfigurasi Gradle di-cache — tiap invoke hemat beberapa detik; paralel aman untuk modul tunggal |
| `gradle/libs.versions.toml` | 4 dependensi hardcoded masuk catalog: telephoto (0.18.0→0.19.0), datastore, reorderable, litert; 4 versi lifecycle digabung satu kunci; versi mati `lifecycleService` dihapus | Konsistensi versi terpusat; telephoto minor bump |
| `app/build.gradle.kts` | `dependenciesInfo { includeInApk = false; includeInBundle = false }` | Blob dependency-info bertanda tangan keluar dari APK/AAB |
| `app/build.gradle.kts` | `ksp { arg("room.schemaLocation", ...) }` | Export schema Room → riwayat migrasi DB tercatat |
| `app/proguard-rules.pro` | Hapus `-keep class DngRawData { *; }` dan rule `native <methods>` | Keduanya redundan: kelas JNI sudah ber-`@Keep` (DngRawData, RawCameraCalibration, fotufilm); rule native methods ada di default `proguard-android-optimize.txt`. R8 kini boleh shrink class DngRawData sesuai reachability |

### Risiko & batasan
- **DataStore dipatok 1.0.0** (bukan bump ke 1.3.0): percobaan bump gagal
  compile — `BackupPreferenceSanitizer.kt` dan `FrameAssetPathMigrator.kt`
  memakai `androidx.datastore.preferences.PreferencesProto` (API internal,
  dihapus di versi lebih baru). Migrasi = rewrite dua file → dicatat sebagai
  utang teknis di README index.
- **LTO sengaja tidak dinyalakan**: sejumlah sumber first-party sengaja
  dikompail `-fno-fast-math -ffp-contract=off` demi bit-parity dengan kernel
  Halide AOT; ThinLTO berpotensi mengimpor semantik fast-math lintas modul
  dan menggeser hasil numerik.
- Flags debug libjpeg-turbo (`CMAKE_C_FLAGS_DEBUG += -O3 -ffast-math`)
  dibiarkan — disengaja agar dependensi berat tetap cepat di build dev.
- Verifikasi: `assembleDevDebug` penuh sukses; `.so` ter-link dengan flag baru.
  **R8/minify release belum dites jalankan** — jalankan `assembleDefaultRelease`
  + smoke test sebelum rilis (proguard rules berubah).

---

## Ringkasan dampak keseluruhan

| Area | Sebelum | Sesudah |
|---|---|---|
| Emisi `CameraState` saat preview stabil/jitter | 30-60 Hz | ≤10 Hz; 0 saat tak berubah |
| 3 loop RAW full-image (12-50 MP) | 1 thread | Paralel per baris (4-8x core big) |
| Startup/refresh daftar kamera | Hingga 8 blok DataStore serial | Baca cache sinkron, 0 blok |
| Tap edit foto (cache miss) | Main thread beku (DB+EXIF) | Async, UI responsif |
| Build increment/ release | Config phase tiap invoke; `-g` di release objek; simbol .so penuh | Config cache; tanpa `-g`; simbol tersembunyi + gc-sections |
