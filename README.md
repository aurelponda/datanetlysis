# Data Analyzer

A small FastAPI web app for inspecting and cleaning CSV files. The Indonesian UI lets users inspect a dataset, choose columns, remove duplicate rows, handle blank cells, preview the result, and download a cleaned CSV.

## Run locally

Requires Python 3.11+.

```sh
python -m venv .venv
# Windows PowerShell: .venv\Scripts\Activate.ps1
# macOS/Linux: source .venv/bin/activate
python -m pip install -e ".[test]"
uvicorn app.main:app --reload
```

Open http://127.0.0.1:8000. Run tests with `python -m pytest`.

## API

- `GET /api/health` returns `{ "status": "ok" }`.
- `POST /api/analyze` accepts a multipart `file` with a `.csv` filename and returns row count, columns, missing counts, numeric summaries, and up to 50 preview rows. Numeric summaries include mean, median, mode, mode frequency/ties, minimum, maximum, range, population and sample variance/standard deviation, Q1, Q3, and IQR.
- `POST /api/clean/preview` accepts a multipart `file`, `selected_columns` (JSON string array), `missing_values` (`keep`, `drop_rows`, or `fill`), `fill_value`, and `remove_duplicates` (boolean). It returns the effect of the selected operations and up to 50 output rows.
- `POST /api/clean/download` accepts the same fields and streams a cleaned CSV download. Preview and download each process the source once, so preview does not store a user file between requests.

All errors use FastAPI's JSON `{ "detail": "..." }` shape. Only CSV is supported. Blank fields count as missing for cleaning; other text such as `NA` remains user data. Duplicate rows are compared exactly after the selected columns and missing-value rule are applied. When deduplication is on, SQLite stores row keys on temporary disk rather than keeping a growing Python set in memory.

## Limits and deployment

`MAX_UPLOAD_BYTES` defaults to 50 MiB, `CSV_CHUNK_ROWS` to 10,000, `MAX_COLUMNS` to 500, `MAX_CONCURRENT_JOBS` to 1, and `MAX_STATS_DB_BYTES` to 64 MiB. The upload middleware caps multipart bodies before FastAPI spools uploads; the endpoint also checks the parsed file size. Mean, variance, minimum, maximum, and range are accumulated per chunk. Exact median, mode, and quartiles require the numeric distribution, so SQLite stores those values temporarily on disk up to `MAX_STATS_DB_BYTES`. If that budget is exceeded, the API returns the streaming statistics and marks exact distribution measures as unavailable instead of growing memory or disk without a bound. The single job slot protects memory and CPU on a small instance; busy requests receive HTTP 503 and can be retried. Increase concurrency only after measuring peak memory with representative data. `CORS_ORIGINS` is empty by default; configure exact trusted origins only if hosting the UI separately.

`docker compose up --build` runs the service as a non-root user with a read-only root filesystem, a bounded temporary filesystem, no added Linux capabilities, and CPU/memory limits. Before public release, put it behind HTTPS and an ingress with matching body-size and request-time limits. Add user authentication, per-user quotas, and distributed rate limiting before allowing public uploads; the current app does not include accounts and is suitable for local development or a trusted test deployment. Avoid logging uploaded content or sensitive CSV data.

CSV input is processed in chunks, so a large file is not loaded as one DataFrame. Memory still scales with the chunk's rows, column widths, and number of columns. Exact deduplication uses temporary disk and adds CPU/disk work proportional to the number of rows. Preview then download repeats the cleaning pass to avoid retaining files between requests. Exact median/mode/quantiles and deduplication can be CPU or disk intensive; for sustained concurrent jobs, move processing to a job queue and give jobs explicit time and storage quotas.

Pandas is suitable while the chunk's working set and required operations fit the CPU and memory budget of one machine. For multi-gigabyte joins, global sort, repeated analytical queries, or exact high-cardinality operations that exceed the machine's limits, evaluate DuckDB for local analytical SQL. Use a distributed engine such as Spark only when the data or throughput exceeds a single machine and the operational cost is justified.

