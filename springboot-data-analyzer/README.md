# Data Analyzer

Aplikasi web ringkas untuk mengunggah dataset CSV/XLSX, meninjau data, menghitung statistik deskriptif, membuat grafik, membersihkan data, dan mengekspor hasil. Backend menggunakan Java dan Spring Boot; frontend adalah HTML, CSS, dan JavaScript tanpa framework.

## Fitur

- Upload `.csv` dan `.xlsx` dengan pemeriksaan ukuran, struktur, dan signature file.
- Preview berpaginasi, pencarian, pengurutan kolom, serta penyaringan.
- Deteksi tipe Numeric, Text, Date, dan Boolean.
- Statistik count, sum, mean, median, mode, minimum, maksimum, range, variance, standard deviation, kuartil, IQR, coefficient of variation, skewness, dan excess kurtosis.
- Grafik bar, line, pie, histogram, scatter, serta box plot.
- Interpretasi otomatis berbahasa Indonesia berdasarkan nilai aktual.
- Laporan missing values dan pembersihan dataset yang dapat dibatalkan dengan reset ke data awal.
- Ekspor CSV, XLSX, dan ringkasan statistik CSV.
- Tema terang/gelap tersimpan di browser, tata letak responsif, dan dukungan navigasi keyboard.
- Seluruh dataset hanya disimpan di memori backend, dengan masa aktif terbatas; file tidak disimpan secara permanen oleh aplikasi.

## Teknologi dan struktur

```text
data-analyzer/
├── backend/
│   ├── pom.xml
│   └── src/main/java/com/dataanalyzer/
│       ├── controller/ApiController.java
│       ├── model/
│       ├── service/
│       └── web/ApiExceptionHandler.java
├── frontend/
│   ├── index.html
│   ├── css/app.css
│   └── js/
├── .github/workflows/
├── LICENSE
└── README.md
```

Chart.js dimuat dari jsDelivr. Aplikasi dapat dibuka tanpa CDN, tetapi grafik memerlukan koneksi ke CDN tersebut.

## Menjalankan secara lokal

Persyaratan: JDK 17 atau lebih baru dan Maven 3.9 atau lebih baru.

```bash
git clone <repository-url>
cd data-analyzer/backend
mvn spring-boot:run
```

Buka <http://localhost:8080>. Untuk membuat paket:

```bash
mvn clean package
java -jar target/data-analyzer-1.0.0.jar
```

Frontend disalin ke dalam aplikasi Spring Boot saat Maven membangun proyek. API dan halaman web berjalan pada origin yang sama.

## Konfigurasi

| Variabel | Default | Fungsi |
|---|---:|---|
| `PORT` | `8080` | Port web server |
| `APP_MAX_UPLOAD_MB` | `10` | Batas ukuran unggahan |
| `APP_MAX_ROWS` | `100000` | Batas jumlah baris |
| `APP_MAX_COLUMNS` | `200` | Batas jumlah kolom |
| `APP_ALLOWED_ORIGINS` | `http://localhost:8080` | Origin frontend yang diizinkan, dipisahkan koma |
| `APP_SESSION_TTL_MINUTES` | `30` | Masa aktif dataset di memori |

Atur variabel lingkungan sebelum menjalankan aplikasi. Hindari menetapkan origin CORS ke `*` untuk deployment publik.

## API

Semua response error berbentuk JSON ramah pengguna tanpa stack trace.

| Method | Endpoint | Keterangan |
|---|---|---|
| `POST` | `/api/datasets` | Upload multipart dengan field `file`; menghasilkan ID dataset, metadata, tipe kolom, dan preview awal |
| `GET` | `/api/datasets/{id}/rows?page=1&pageSize=25&search=` | Halaman data; parameter opsional `sortBy`, `direction`, `filterColumn`, `operator`, `value` |
| `GET` | `/api/datasets/{id}/statistics?columns=Score,Age` | Statistik satu atau beberapa kolom |
| `POST` | `/api/datasets/{id}/charts` | Body `{ "type":"histogram", "column":"Score", "xColumn":"Age", "yColumn":"Score", "bins":10 }` |
| `POST` | `/api/datasets/{id}/clean` | Body JSON dengan `removeDuplicates`, `removeColumns`, `renames`, `missingStrategy` (`ignore`, `removeRows`, `mean`, `median`, `mode`) |
| `POST` | `/api/datasets/{id}/reset` | Memulihkan salinan asli dataset |
| `GET` | `/api/datasets/{id}/missing` | Jumlah dan persentase nilai kosong per kolom |
| `GET` | `/api/datasets/{id}/export.csv` | Ekspor data aktif ke CSV |
| `GET` | `/api/datasets/{id}/export.xlsx` | Ekspor data aktif ke XLSX |
| `GET` | `/api/datasets/{id}/summary.csv` | Ekspor ringkasan statistik |
| `DELETE` | `/api/datasets/{id}` | Menghapus dataset dari memori |

Variance dan standard deviation menggunakan rumus populasi. Kuartil dihitung dengan interpolasi linear pada posisi `(n - 1) × p`. Mode mengembalikan nilai terendah jika beberapa nilai memiliki frekuensi tertinggi. Missing values diabaikan saat statistik dihitung.

## Deployment ke Railway

`Dockerfile` membangun backend Spring Boot dan menyertakan frontend pada satu aplikasi yang sama. Frontend dan API memakai origin yang sama, sehingga konfigurasi API bawaan tetap kosong.

Untuk deploy, pilih repository ini di Railway dengan root directory folder proyek, lalu biarkan Railway membangun `Dockerfile`. Aplikasi membaca port dari variabel `PORT` Railway. Atur healthcheck path ke `/`; route tersebut menyajikan halaman utama. Deployment menggunakan satu instance karena sesi dataset disimpan sementara di memori.

Untuk GitHub Pages, jalankan workflow `pages.yml` dan tetapkan URL backend HTTPS pada `window.DATA_ANALYZER_API_BASE_URL` di `frontend/js/config.js`. Tambahkan URL halaman Pages tersebut ke `APP_ALLOWED_ORIGINS` di backend. Mode Pages memerlukan backend terpisah; mode Railway menyajikan frontend dan backend bersama.

## Menambah fitur

Tambahkan endpoint di `ApiController`, logika domain di service, dan komponen browser di `frontend/js`. Pertahankan batas ukuran input, validasi isi file, output error tanpa stack trace, serta uji fungsi domain statistik dengan unit test.

## Batasan

- Batas upload default 10 MB, 100.000 baris, dan 200 kolom; server menjalankan parsing dan perhitungan di memori.
- Sesi dataset bersifat sementara dan terikat pada satu instance backend. Belum ada login, penyimpanan persisten, atau berbagi dataset antar-instance.
- File yang diunggah tidak dieksekusi. Apache POI hanya membaca workbook `.xlsx`; macro workbook tidak didukung.
- Interpretasi adalah ringkasan eksploratif, bukan kesimpulan kausal atau pengganti penilaian ahli.
