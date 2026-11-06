# Fitur: Ikon Disable-All-Effects di Top Bar Kamera

**Tanggal:** 2026-11-07
**Commit:** `4abbf162d` — feat: one-tap disable-all-effects button in camera top bar

---

## Context

Butuh shortcut satu-tap untuk membersihkan tampilan kamera dari semua efek
tanpa masuk menu. Lokasi: top bar kamera (mode foto **dan** video).

Cakupan (keputusan user): LUT → None, independent effects → default,
frame → off. Color recipe per-LUT (kontras/curves/HSL yang diedit) **tidak**
disentuh — pilih LUT lagi = look-nya kembali utuh.

## Perubahan

### 1. `CameraViewModel.disableAllEffects()` (baru)
- `setLut(null)` bila LUT aktif — `applyLut("none")` sinkron di controller,
  persist ke slot foto/video sesuai mode (reuse `setLut` :3959).
- Reset independent effects di **recipe slot "none"** — slot yang dipakai
  tampilan tanpa LUT: `loadColorRecipeParams("none")` →
  `EffectParams.DEFAULT.applyTo(...)` → `saveColorRecipeParams` (skip bila
  sudah default). Pref `activeEffectParams` direset (dipakai preset matching).
- `setFrame(null)` — frame overlay off (reuse :4443).
- Preset tidak auto-restore (terverifikasi: `applyPreset` hanya jalan dari
  UI eksplisit) — efek samping hanya badge `*` modified di tab Presets.

### 2. `CameraTopBar.kt`
- Param baru `effectsActive`, `onDisableEffectsClick`.
- **Photo bar**: tombol antara Histogram dan Settings — `AppIcons.FilterNone`,
  20.dp, tint `AccentColor` bila `effectsActive` (feedback ada efek aktif),
  putih bila bersih.
- **VideoTopBar**: tombol sama di quick-toggle row sebelum Settings
  (hidden saat recording — HUD menggantikan row).

### 3. `CameraScreen.kt` call site
`effectsActive = currentLutId != "none" || currentFrameId != null ||
!currentEffectParams.isDefault()` — ketiganya state observable
(`currentLutId` StateFlow, `currentEffectParams` StateFlow,
`currentFrameId` mutableStateOf) → ikon recompose otomatis.

### 4. Strings
`disable_all_effects` di `values` (en), `values-in`, `values-zh-rCN`.

## Dampak

- Satu tap = preview & hasil foto langsung bersih; tidak perlu buka panel
  filter → pilih None → buka editor efek → reset → panel frame → off.
- State persist via jalur simpan existing — tetap bersih setelah restart.

## Risiko & batasan

- Effects yang tersimpan di recipe slot LUT lain tidak dibersihkan (by
  design — hanya tampilan aktif yang dimatikan).
- Saat video merekam, tombol tidak tersedia (HUD menutup quick-toggle);
  gunakan sebelum mulai rekam.
- Verifikasi: compile + `assembleDefaultRelease` BUILD SUCCESSFUL. Uji
  perangkat tertunda — device terputus saat commit; install ulang saat
  USB tersambung.
