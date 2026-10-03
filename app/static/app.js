const form = document.querySelector("#upload-form");
const input = document.querySelector("#csv-file");
const status = document.querySelector("#status");
const results = document.querySelector("#results");
const cleaner = document.querySelector("#cleaner");
const cleanStatus = document.querySelector("#clean-status");
const cleanSummary = document.querySelector("#clean-summary");
const previewButton = document.querySelector("#preview-clean");
const downloadButton = document.querySelector("#download-clean");
const fileName = document.querySelector("#file-name");
const maxBytes = 50 * 1024 * 1024;

input.addEventListener("change", () => {
  fileName.textContent = input.files[0]?.name ?? "Belum ada file dipilih";
  cleaner.hidden = true;
  results.hidden = true;
});
document.querySelector("#missing-values").addEventListener("change", (event) => {
  document.querySelector("#fill-value-wrap").hidden = event.target.value !== "fill";
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  results.replaceChildren(); cleaner.hidden = true;
  const file = selectedFile();
  if (!file) return;
  const button = form.querySelector("button");
  button.disabled = true; status.textContent = "Sedang menganalisis…";
  try {
    const response = await fetch("/api/analyze", { method: "POST", body: makeFormData(file) });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.detail || "Analisis gagal.");
    status.textContent = "";
    renderResults(payload);
    buildColumnOptions(payload.columns);
    cleaner.hidden = false;
  } catch (error) { status.textContent = error.message || "Tidak dapat terhubung ke server."; }
  finally { button.disabled = false; }
});

previewButton.addEventListener("click", async () => {
  const file = selectedFile();
  if (!file) return;
  previewButton.disabled = true; downloadButton.hidden = true; cleanSummary.replaceChildren(); cleanSummary.hidden = true;
  cleanStatus.textContent = "Menerapkan aturan dan menyiapkan preview…";
  try {
    const response = await fetch("/api/clean/preview", { method: "POST", body: cleaningFormData(file) });
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "Preview gagal dibuat.");
    renderCleanSummary(data);
    cleanStatus.textContent = "Periksa hasil. File asli tidak berubah.";
    downloadButton.hidden = false;
  } catch (error) { cleanStatus.textContent = error.message || "Tidak dapat membuat preview."; }
  finally { previewButton.disabled = false; }
});

downloadButton.addEventListener("click", async () => {
  const file = selectedFile();
  if (!file) return;
  downloadButton.disabled = true; cleanStatus.textContent = "Menyiapkan file unduhan…";
  try {
    const response = await fetch("/api/clean/download", { method: "POST", body: cleaningFormData(file) });
    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.detail || "Unduhan gagal.");
    }
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement("a"); link.href = url; link.download = "cleaned.csv"; link.click();
    URL.revokeObjectURL(url);
    cleanStatus.textContent = "File CSV bersih berhasil diunduh.";
  } catch (error) { cleanStatus.textContent = error.message || "Unduhan gagal."; }
  finally { downloadButton.disabled = false; }
});

function selectedFile() {
  const file = input.files[0];
  if (!file || !file.name.toLowerCase().endsWith(".csv")) { status.textContent = "Pilih file CSV terlebih dahulu."; return null; }
  if (file.size > maxBytes) { status.textContent = "Ukuran file melebihi batas 50 MB."; return null; }
  status.textContent = "";
  return file;
}

function makeFormData(file) { const body = new FormData(); body.append("file", file); return body; }

function cleaningFormData(file) {
  const body = makeFormData(file);
  const selected = [...document.querySelectorAll("#column-options input:checked")].map((item) => item.value);
  body.append("selected_columns", JSON.stringify(selected));
  body.append("missing_values", document.querySelector("#missing-values").value);
  body.append("fill_value", document.querySelector("#fill-value").value);
  body.append("remove_duplicates", document.querySelector("#remove-duplicates").checked);
  return body;
}

function buildColumnOptions(columns) {
  const container = document.querySelector("#column-options"); container.replaceChildren();
  for (const [index, column] of columns.entries()) {
    const label = document.createElement("label");
    const checkbox = document.createElement("input"); checkbox.type = "checkbox"; checkbox.value = column; checkbox.checked = true;
    checkbox.id = `column-${index}`;
    const name = document.createElement("span"); name.textContent = column;
    label.append(checkbox, name); container.append(label);
  }
}

function renderResults(data) {
  const section = document.createElement("div"); section.className = "results";
  const metrics = document.createElement("div"); metrics.className = "metrics";
  for (const [label, value] of [["Baris", data.rows], ["Kolom", data.columns.length], ["Kolom numerik", Object.keys(data.numeric_summary).length]]) {
    const card = document.createElement("div"); card.className = "metric";
    const title = document.createElement("span"); title.textContent = label;
    const number = document.createElement("strong"); number.textContent = Number(value).toLocaleString("id-ID");
    card.append(title, number); metrics.append(card);
  }
  const note = document.createElement("p");
  note.className = "stats-note";
  note.textContent = data.statistics_note || "Varians dan simpangan baku ditampilkan untuk populasi dan sampel (n−1). Kuartil memakai interpolasi linear; jika semua nilai unik, modus tidak ditampilkan.";
  section.append(
    metrics,
    note,
    makeTable(
      "Ukuran pemusatan dan penyebaran",
      ["Kolom", "Jumlah", "Rata-rata", "Median", "Modus", "Frekuensi modus", "Modus seri", "Minimum", "Maksimum", "Rentang", "Varians populasi", "Simpangan baku populasi", "Varians sampel", "Simpangan baku sampel", "Q1", "Q3", "IQR"],
      Object.entries(data.numeric_summary).map(([name, stats]) => [
        name, stats.count, stats.mean, stats.median, stats.mode, stats.mode_frequency, stats.mode_tie_count,
        stats.min, stats.max, stats.range, stats.variance_population, stats.stddev_population,
        stats.variance_sample, stats.stddev_sample, stats.q1, stats.q3, stats.iqr,
      ]),
    ),
  );
  section.append(makeTable("Preview data", data.columns, data.preview.map((row) => data.columns.map((column) => row[column] ?? ""))));
  results.append(section); results.hidden = false;
}

function renderCleanSummary(data) {
  cleanSummary.replaceChildren();
  const metrics = document.createElement("div"); metrics.className = "metrics clean-metrics";
  for (const [label, value] of [["Baris awal", data.rows_before], ["Baris hasil", data.rows_after], ["Duplikat dihapus", data.duplicates_removed], ["Baris kosong dihapus", data.missing_rows_removed]]) {
    const card = document.createElement("div"); card.className = "metric";
    const title = document.createElement("span"); title.textContent = label;
    const number = document.createElement("strong"); number.textContent = Number(value).toLocaleString("id-ID");
    card.append(title, number); metrics.append(card);
  }
  cleanSummary.append(metrics, makeTable("Preview hasil bersih", data.columns, data.preview.map((row) => data.columns.map((column) => row[column] ?? ""))));
  cleanSummary.hidden = false;
}

function makeTable(title, headers, rows) {
  const panel = document.createElement("article"); panel.className = "panel";
  const heading = document.createElement("h2"); heading.textContent = title;
  const wrap = document.createElement("div"); wrap.className = "table-wrap";
  const table = document.createElement("table"); const head = document.createElement("thead"); const headerRow = document.createElement("tr");
  for (const text of headers) { const cell = document.createElement("th"); cell.textContent = text; headerRow.append(cell); }
  head.append(headerRow); table.append(head);
  const body = document.createElement("tbody");
  for (const row of rows) { const tr = document.createElement("tr"); for (const value of row) { const td = document.createElement("td"); td.textContent = value == null ? "" : String(value); tr.append(td); } body.append(tr); }
  table.append(body); wrap.append(table); panel.append(heading, wrap); return panel;
}
