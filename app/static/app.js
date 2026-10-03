const form = document.querySelector("#upload-form");
const input = document.querySelector("#csv-file");
const status = document.querySelector("#status");
const results = document.querySelector("#results");
const fileName = document.querySelector("#file-name");
const maxBytes = 50 * 1024 * 1024;

input.addEventListener("change", () => { fileName.textContent = input.files[0]?.name ?? "No file selected"; });
form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const file = input.files[0];
  results.replaceChildren();
  if (!file || !file.name.toLowerCase().endsWith(".csv")) { status.textContent = "Choose a CSV file."; return; }
  if (file.size > maxBytes) { status.textContent = "File exceeds the 50 MB limit."; return; }
  const button = form.querySelector("button");
  button.disabled = true; status.textContent = "Analyzing…";
  try {
    const body = new FormData(); body.append("file", file);
    const response = await fetch("/api/analyze", { method: "POST", body });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.detail || "Analysis failed.");
    status.textContent = "";
    renderResults(payload);
  } catch (error) { status.textContent = error.message || "Could not connect to the server."; }
  finally { button.disabled = false; }
});

function renderResults(data) {
  const section = document.createElement("div"); section.className = "results";
  const metrics = document.createElement("div"); metrics.className = "metrics";
  for (const [label, value] of [["Rows", data.rows], ["Columns", data.columns.length], ["Numeric fields", Object.keys(data.numeric_summary).length]]) {
    const card = document.createElement("div"); card.className = "metric";
    const title = document.createElement("span"); title.textContent = label;
    const number = document.createElement("strong"); number.textContent = Number(value).toLocaleString();
    card.append(title, number); metrics.append(card);
  }
  section.append(metrics, makeTable("Numeric summary", ["Column", "Count", "Mean", "Min", "Max"], Object.entries(data.numeric_summary).map(([name, stats]) => [name, stats.count, stats.mean, stats.min, stats.max])));
  section.append(makeTable("Data preview", data.columns, data.preview.map(row => data.columns.map(column => row[column] ?? ""))));
  results.append(section); results.hidden = false;
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
