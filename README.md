# Ringan Browser

Browser Android **sangat ringan** berbasis WebView sistem, untuk HP jadul (Android 5.0+, RAM 512 MB).

- Tanpa library tambahan (tanpa AndroidX/GeckoView): APK kecil, satu APK untuk semua arsitektur (ARM 32/64, x86)
- Hanya satu WebView hidup pada satu waktu, maksimal 3 tab
- Mode hemat data (blokir gambar) dan pemblokir iklan berbasis host
- Tab: baris tab di bawah kolom URL (ketuk = pindah, tahan = tutup, + = tab baru), maksimal 8 tab
- Bookmark: tambah, ubah judul, hapus, buka di tab baru, tampil sebagai kotak di beranda
- Beranda kustom: judul, warna, mesin pencari, tampil bookmark, atau pakai halaman web sendiri
- Video: tombol fullscreen situs memutar otomatis ke landscape; tombol "Video" memaksa video apa pun layar penuh landscape
- Mode Ultra Ringan (satu ketukan): blokir gambar, iklan, autoplay, dan Facebook dialihkan ke m.facebook.com. Juga ada JavaScript per situs dan pilihan tampilan HP/Desktop
- Download manager (DownloadManager Android), hapus data penjelajahan

## Build
1. Upload isi folder ini ke repo GitHub, branch `main`.
2. Workflow **Build (Debug APK)** jalan otomatis; unduh APK dari *Artifacts*.
3. Rilis: `git tag v0.2.0 && git push origin v0.2.0`.

## Catatan
- Proyek ini belum dikompilasi saat dibuat. Jika build gagal, kirim log error dari Actions.
- Mesin web = Android System WebView. Di Android 5 pembaruannya lewat Play Store (jika ada); tanpa pembaruan, beberapa situs modern bisa tampil kurang baik.
- Ekstensi browser tidak didukung (khusus mesin Firefox, sudah dihapus agar ringan).
- Download konten ber-DRM atau dari layanan yang melarangnya tidak didukung.
