# Data Analyzer

Small web application for analyzing CSV files. FastAPI serves a static frontend and a JSON API. CSV data is processed in bounded pandas chunks; the service retains only a 100-row preview and compact aggregates.

## Run locally

Requires Python 3.11+.

```sh
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
pip install -e ".[test]"
uvicorn app.main:app --reload
```

Open http://127.0.0.1:8000. Run tests with `pytest`.

## API

- `GET /api/health` returns `{ "status": "ok" }`.
- `POST /api/analyze` accepts a multipart `file` with a `.csv` filename and returns row count, columns, missing counts, numeric summaries, and up to 100 preview rows.

Errors use FastAPI's consistent JSON shape `{ "detail": "..." }` and HTTP status codes. Only CSV is supported in this first version; spreadsheet formats are not silently interpreted as CSV.

## Limits and deployment

`MAX_UPLOAD_BYTES` defaults to 50 MiB, `CSV_CHUNK_ROWS` to 10,000, and `MAX_COLUMNS` to 500. Set `CORS_ORIGINS` to a comma-separated list only when the UI is hosted on a separate trusted origin; same-origin deployment needs no CORS exception. Place a reverse proxy/API gateway in front and set its body-size and request-time limits to match the app. Deploy with `docker compose up --build`; the container runs as a non-root user with a read-only root filesystem, bounded temporary storage, and CPU/memory limits.

The chunk size bounds the DataFrame working set but does not bound total processing time. One synchronous analysis job can still consume CPU for the duration of the request. For sustained concurrent workloads, add a job queue and per-user quotas; for multi-gigabyte files, high-cardinality exact group-bys, joins, or interactive repeated queries, use a query engine such as DuckDB for local analytical SQL or a distributed engine such as Spark when data exceeds one machine. Keep pandas for datasets whose chunk aggregates and required operations fit a single machine's memory and CPU budget.

## Security notes

The server validates extension, enforces a byte limit while streaming to a generated temporary path, never trusts the client filename as a path, parses in a worker thread, and deletes the temporary file on success or failure. The UI inserts returned values using `textContent` to avoid interpreting uploaded strings as HTML. Add authentication and rate limiting before exposing uploads to an untrusted public audience.

