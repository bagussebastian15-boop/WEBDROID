# Ringan Browser

Browser Android ringan berbasis **GeckoView** (mesin Firefox), dengan:
- Tab, bookmark, riwayat navigasi
- Dukungan **ekstensi WebExtensions** (pasang dari URL `.xpi`, tautan `.xpi`, atau menu tahan-lama pada link)
- Download manager sendiri (file media langsung, tanpa batas jumlah), tersimpan di folder Download
- **Mesin download ala IDM** (`DownloadCenter.kt`): file dipecah jadi banyak potongan dan diunduh lewat beberapa koneksi paralel (HTTP Range, default 8, bisa 4/8/16/32 lewat menu ⋮ → Koneksi download). Koneksi yang selesai langsung mengambil potongan berikutnya, jadi semua koneksi selalu sibuk. Bisa dijeda/dilanjutkan (juga setelah aplikasi ditutup), otomatis mengulang saat koneksi putus, dan turun ke 1 koneksi jika server tidak mendukung Range.
- Info download: strip progres (nama, persen, ukuran, kecepatan, sisa waktu), daftar **Unduhan** di menu ⋮ (ketuk = jeda/lanjut/buka, tahan = batal/hapus), notifikasi progres di latar belakang, dan notifikasi selesai/gagal beserta alasannya
- Adblock: pasang ekstensi seperti uBlock Origin

## Struktur
```
app/                      kode aplikasi (Kotlin)
.github/workflows/
  build.yml               build APK debug tiap push/PR
  lint.yml                Android Lint (PR)
  release.yml             APK release + GitHub Release saat tag v*
  geckoview-update.yml    cek versi GeckoView baru tiap minggu
```

## Cara pakai di GitHub
1. Buat repo baru, upload semua isi folder ini, push ke branch `main`.
2. Tab **Actions** -> workflow **Build (Debug APK)** jalan otomatis. Unduh APK dari *Artifacts*.
3. Rilis: `git tag v0.1.0 && git push origin v0.1.0` -> APK muncul di *Releases*.
4. (Opsional) Tanda tangan sendiri: isi secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Build lokal: Android Studio, atau `gradle assembleDebug` (JDK 17). Untuk membuat Gradle wrapper: `gradle wrapper --gradle-version 8.7`.

## Catatan penting
- Proyek ini **belum dikompilasi** saat dibuat. Jika build gagal, bagian yang paling mungkin perlu penyesuaian ada di `MainActivity.kt` (delegate GeckoView & ekstensi, bergantung versi). Kirim log error dari Actions.
- Versi GeckoView diatur di `gradle.properties` (`geckoviewVersion`). Naikkan ke versi terbaru untuk patch keamanan.
- Ekstensi: hanya ekstensi Firefox (WebExtensions) yang ditandatangani Mozilla secara default. Ekstensi Chrome Web Store tidak otomatis jalan.
- Popup/toolbar ekstensi belum ada UI-nya; ekstensi berbasis content script/background (adblock, dll.) tetap berjalan.
- Download konten ber-DRM atau dari layanan yang melarangnya (mis. YouTube, Spotify) tidak didukung.
- Download memakai User-Agent Firefox dan Referer halaman asal, tetapi **cookie login tidak diteruskan** (GeckoView tidak membagikannya). Link yang butuh login/cookie bisa gagal dengan HTTP 403.
