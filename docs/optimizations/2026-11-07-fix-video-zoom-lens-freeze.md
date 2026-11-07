# Bugfix: Video Freeze Saat Zoom Lintas Lensa

**Tanggal:** 2026-11-07
**Commit:** `0d8ce1ce8` — fix(video): defer lens-boundary session recreate while recording
**Revisi 2:** `40019938e` — clamp recording zoom ke window physical aktif
**Revisi 3 (akar sebenarnya):** `5b751fbfa` — jangan masuk LensSwitchTransition saat rekam

---

## Revisi 3 — akar masalah sebenarnya (`5b751fbfa`)

Gejala tersisa: **preview freeze** saat pindah lensa. Penyebab bukan session:

`LensSwitchTransition.switchLens` menampilkan overlay frame beku (PixelCopy)
lalu menunggu `state.first { currentCameraId == target && firstFrame baru }`.
Guard ViewModel yang mencegah switch saat rekam membuat callback `return`
tanpa pindah — kondisi wait tak pernah terpenuhi → overlay beku menutupi
viewfinder tanpa batas.

Fix: ketiga helper UI (`switchToLensWithPreviewTransition`,
`setZoomWithPreviewTransition`, `animateZoomStopWithPreviewTransition`)
short-circuit saat `videoRecordingState.isRecording` — hanya animasi zoom
dalam lensa aktif, tanpa overlay/transition. Guard ViewModel dipertahankan
sebagai safety net. Diverifikasi user di device.

---

## Lapisan 1-2 (`0d8ce1ce8`, `40019938e`)

- **Lapisan 1**: defer session recreate lintas lensa saat rekam
  (`pendingPhysicalZoomRestart`, apply di `stopVideoRecording`).
- **Lapisan 2**: clamp zoom ke window physical aktif saat rekam —
  `CONTROL_ZOOM_RATIO` yang melewati midpoint antar physical membuat HAL
  internal-switch physical padahal stream ter-bound ke id lama → frame
  encoder berhenti.

---

## Gejala

Saat merekam video, pinch-zoom melewati batas lensa (mis. 1x wide → 3x tele,
via lensa virtual ISZ atau bound physical camera) membuat **hasil video
membeku** di frame terakhir — recording ticker tetap berjalan, file tetap
ditulis, tapi isi gambar tidak berubah lagi.

## Root cause

Rantai kegagalan (semua `Camera2Controller.kt` kecuali disebut):

1. `setZoomRatio` → `recreateSessionForPhysicalZoomIfNeeded` (:6386) —
   **tanpa guard `isRecording`** (beda dengan path vendor session-param yang
   sudah defer saat rekam, :5399/:5446).
2. Bound physical camera berubah (`CameraState.getBoundPhysicalCameraId`) →
   `safeCloseCaptureSession` + `createPreviewSession` dijalankan di tengah
   rekaman, menyarangkan session baru dengan `setPhysicalCameraId(newId)`
   di atas surface encoder yang sedang dikonsumsi MediaCodec.
3. HAL diam-diam berhenti mengirim frame ke surface input encoder
   (kegagalan total akan force-stop via `onConfigureFailed`; ini yang
   senyap). `onRealtimeFrameAvailable` tak pernah dipanggil lagi
   (VideoRecorder.kt:386-407) → `pendingRealtimeFrameCount == 0`, render
   loop keluar tanpa error, muxer idle, PTS terakhir membeku.
4. Satu-satunya watchdog frame hanya ada di jalur EIS+ YUV
   (`scheduleEnhancedStabilizationInputWatchdog`); jalur dedicated-surface
   (default) tidak punya. `onCameraInputStarted` one-shot latch — session
   baru tidak pernah re-handshake.
5. Preview ikut beku: `invalidateCaptureSession` null-kan
   `_enginePreviewSource` dan session baru tak menghasilkan frame.

## Perbaikan (pola yang sudah ada di codebase)

1. **Defer recreate** — `recreateSessionForPhysicalZoomIfNeeded`:
   saat `videoRecordingState.isRecording`, set `pendingPhysicalZoomRestart`
   + return false tanpa menyentuh session. Zoom request tetap di-apply via
   `CONTROL_ZOOM_RATIO`/crop **dalam lensa aktif** — pinch tetap zoom
   (digital) sampai batas lensa, rekaman tidak terputus.
2. **Apply deferred** — `stopVideoRecording`: setelah `videoRecorder
   .stopRecording()`, jika `pendingPhysicalZoomRestart` → panggil
   `recreateSessionForPhysicalZoomIfNeeded` (berdampingan dengan handler
   `pendingVendorSessionParameterRestart`). Flag di-reset di
   `createPreviewSession`.
3. **Guard lens switch UI** — `CameraViewModel.switchToLens` /
   `switchToLensAndSetZoomRatio`: saat rekam, jangan close/reopen camera
   (freeze mekanisme sama); hanya apply zoom ratio dalam lensa — konsisten
   dengan perilaku lens-lock.

## Dampak

- Recording zoom lintas batas lensa: video terus merekam tanpa freeze;
  lensa fisik berpindah otomatis **setelah** stop (sesi di-recreate),
  sesudahnya zoom kembali penuh lintas lensa.
- Perilaku selama rekam = "pin lensa" (seperti opsi lens lock), tapi
  selalu aktif — pilihan aman karena switch mid-recording memang tidak
  bisa dilakukan tanpa memutus encoder.

## Risiko & batasan

- Selama rekam, kualitas zoom melewati batas lensa = digital crop dalam
  lensa aktif (bukan optik lensa berikutnya). Itu trade-off yang disengaja.
- Tidak menambah watchdog frame untuk jalur dedicated-surface — masih
  bisa beku oleh penyebab lain (HAL bug). Kandidat tindak lanjut: watchdog
  `statsIncomingFrames` + re-arm `onCameraInputStarted`.
- Verifikasi: compile + `assembleDefaultRelease` sukses. **Uji perangkat
  tertunda** — device terputus saat commit; install ulang saat USB balik.
