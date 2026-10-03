from __future__ import annotations

import asyncio
import csv
import json
import math
import os
import sqlite3
import tempfile
from decimal import Decimal, localcontext
from pathlib import Path
from typing import Any

import pandas as pd
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.background import BackgroundTask
from starlette.concurrency import run_in_threadpool
from starlette.types import ASGIApp, Receive, Scope, Send

MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_BYTES", str(50 * 1024 * 1024)))
CHUNK_ROWS = int(os.getenv("CSV_CHUNK_ROWS", "10000"))
MAX_COLUMNS = int(os.getenv("MAX_COLUMNS", "500"))
MAX_PREVIEW_ROWS = 50
MAX_PREVIEW_CELL_CHARS = 200
MAX_CONCURRENT_JOBS = int(os.getenv("MAX_CONCURRENT_JOBS", "1"))
JOB_WAIT_SECONDS = float(os.getenv("JOB_WAIT_SECONDS", "2"))
MAX_STATS_DB_BYTES = int(os.getenv("MAX_STATS_DB_BYTES", str(64 * 1024 * 1024)))
if min(MAX_UPLOAD_BYTES, CHUNK_ROWS, MAX_COLUMNS, MAX_CONCURRENT_JOBS, MAX_STATS_DB_BYTES) <= 0 or JOB_WAIT_SECONDS <= 0:
    raise RuntimeError("Upload, chunk, column, concurrency, statistics-disk limits, and wait timeout must be positive")
APP_DIR = Path(__file__).parent
job_slots = asyncio.Semaphore(MAX_CONCURRENT_JOBS)

app = FastAPI(title="Data Analyzer", version="0.2.0")
allowed_origins = [origin.strip() for origin in os.getenv("CORS_ORIGINS", "").split(",") if origin.strip()]
if allowed_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=allowed_origins,
        allow_credentials=False,
        allow_methods=["GET", "POST"],
        allow_headers=["Content-Type"],
    )


class _PayloadTooLarge(Exception):
    pass


class UploadLimitMiddleware:
    """Cap the whole multipart body before FastAPI spools an UploadFile to disk."""

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        path = scope.get("path", "")
        if scope["type"] != "http" or scope.get("method") != "POST" or not path.startswith(("/api/analyze", "/api/clean/")):
            await self.app(scope, receive, send)
            return

        limit = MAX_UPLOAD_BYTES + 1024 * 1024  # allow multipart headers and boundaries
        headers = dict(scope.get("headers", []))
        raw_length = headers.get(b"content-length")
        if raw_length:
            try:
                if int(raw_length) > limit:
                    await JSONResponse(status_code=413, content={"detail": "Request exceeds the upload size limit."})(scope, receive, send)
                    return
            except ValueError:
                await JSONResponse(status_code=400, content={"detail": "Invalid Content-Length header."})(scope, receive, send)
                return

        received = 0
        overflow = False

        async def guarded_receive():
            nonlocal received, overflow
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    overflow = True
                    raise _PayloadTooLarge()
            return message

        async def guarded_send(message):
            if not overflow:
                await send(message)

        try:
            await self.app(scope, guarded_receive, guarded_send)
        except _PayloadTooLarge:
            pass
        if overflow:
            await JSONResponse(status_code=413, content={"detail": "Request exceeds the upload size limit."})(scope, receive, send)


app.add_middleware(UploadLimitMiddleware)


@app.middleware("http")
async def add_security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
    response.headers.setdefault(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
    )
    return response


@app.get("/api/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


async def _validate_upload(file: UploadFile) -> None:
    if not file.filename or Path(file.filename).suffix.lower() != ".csv":
        raise HTTPException(status_code=415, detail="Only .csv files are supported.")
    if file.size == 0:
        raise HTTPException(status_code=400, detail="The uploaded file is empty.")
    if file.size is not None and file.size > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="File exceeds the upload size limit.")


async def _run_limited(function, *args):
    try:
        await asyncio.wait_for(job_slots.acquire(), timeout=JOB_WAIT_SECONDS)
    except TimeoutError as exc:
        raise HTTPException(status_code=503, detail="Analyzer is busy. Please retry shortly.") from exc
    try:
        return await run_in_threadpool(function, *args)
    finally:
        job_slots.release()


@app.post("/api/analyze")
async def analyze(file: UploadFile = File(...)) -> dict[str, Any]:
    """Analyze a CSV incrementally; retain only compact aggregates and a capped preview."""
    try:
        await _validate_upload(file)
        return await _run_limited(_analyze_csv, file.file)
    except (UnicodeDecodeError, pd.errors.ParserError, pd.errors.EmptyDataError, ValueError) as exc:
        raise HTTPException(status_code=422, detail="Could not parse this CSV file.") from exc
    finally:
        await file.close()


@app.post("/api/clean/preview")
async def clean_preview(
    file: UploadFile = File(...),
    selected_columns: str = Form("[]"),
    missing_values: str = Form("keep"),
    fill_value: str = Form(""),
    remove_duplicates: bool = Form(False),
) -> dict[str, Any]:
    """Preview the effect of CSV cleaning without retaining the uploaded file."""
    try:
        await _validate_upload(file)
        options = _clean_options(selected_columns, missing_values, fill_value, remove_duplicates)
        with tempfile.TemporaryDirectory(prefix="data-analyzer-preview-") as temp_dir:
            output_path = str(Path(temp_dir) / "cleaned.csv")
            result = await _run_limited(_clean_csv, file.file, output_path, options)
            return result
    except (UnicodeDecodeError, pd.errors.ParserError, pd.errors.EmptyDataError, ValueError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=422, detail="Could not process these CSV cleaning options.") from exc
    finally:
        await file.close()


@app.post("/api/clean/download")
async def clean_download(
    file: UploadFile = File(...),
    selected_columns: str = Form("[]"),
    missing_values: str = Form("keep"),
    fill_value: str = Form(""),
    remove_duplicates: bool = Form(False),
) -> FileResponse:
    """Reprocess and stream the cleaned CSV; temporary output is deleted after transfer."""
    temp_dir = None
    try:
        await _validate_upload(file)
        options = _clean_options(selected_columns, missing_values, fill_value, remove_duplicates)
        temp_dir = tempfile.TemporaryDirectory(prefix="data-analyzer-download-")
        output_path = str(Path(temp_dir.name) / "cleaned.csv")
        await _run_limited(_clean_csv, file.file, output_path, options)
        return FileResponse(
            output_path,
            media_type="text/csv; charset=utf-8",
            filename="cleaned.csv",
            background=BackgroundTask(temp_dir.cleanup),
        )
    except (UnicodeDecodeError, pd.errors.ParserError, pd.errors.EmptyDataError, ValueError, json.JSONDecodeError) as exc:
        if temp_dir:
            temp_dir.cleanup()
        raise HTTPException(status_code=422, detail="Could not process these CSV cleaning options.") from exc
    except Exception:
        if temp_dir:
            temp_dir.cleanup()
        raise
    finally:
        await file.close()


def _clean_options(selected_columns: str, missing_values: str, fill_value: str, remove_duplicates: bool) -> dict[str, Any]:
    columns = json.loads(selected_columns)
    if columns is not None and (not isinstance(columns, list) or any(not isinstance(name, str) for name in columns)):
        raise ValueError("selected_columns must be a list of column names")
    if columns is not None and len(columns) != len(set(columns)):
        raise ValueError("selected_columns cannot contain duplicates")
    if missing_values not in {"keep", "drop_rows", "fill"}:
        raise ValueError("unsupported missing value strategy")
    if len(fill_value) > 500:
        raise ValueError("fill value is too long")
    return {"selected_columns": columns, "missing_values": missing_values, "fill_value": fill_value, "remove_duplicates": remove_duplicates}


def _read_header(file_obj) -> list[str]:
    file_obj.seek(0)
    columns = [str(column) for column in pd.read_csv(file_obj, nrows=0, encoding="utf-8-sig").columns]
    if not columns or len(columns) > MAX_COLUMNS:
        raise ValueError("Invalid or excessive number of columns")
    file_obj.seek(0)
    return columns


def _analyze_csv(file_obj) -> dict[str, Any]:
    columns = _read_header(file_obj)
    row_count = 0
    preview: list[dict[str, Any]] = []
    missing: dict[str, int] = {column: 0 for column in columns}
    numeric: dict[str, dict[str, Any]] = {}
    numeric_db = None
    db_path: str | None = None
    exact_distribution = True

    try:
        for chunk in pd.read_csv(file_obj, chunksize=CHUNK_ROWS, encoding="utf-8-sig"):
            row_count += len(chunk)
            if len(preview) < MAX_PREVIEW_ROWS:
                head = chunk.head(MAX_PREVIEW_ROWS - len(preview)).astype(object)
                clean_head = head.where(pd.notna(head), None).to_dict(orient="records")
                for row in clean_head:
                    preview.append({key: _preview_value(value) for key, value in row.items()})
            for column in columns:
                missing[column] += int(chunk[column].isna().sum())
            for column_index, column in enumerate(chunk.select_dtypes(include="number").columns):
                values = chunk[column].dropna()
                finite_values = [float(value) for value in values if math.isfinite(float(value))]
                missing[column] += len(values) - len(finite_values)
                if not finite_values:
                    continue

                aggregate = numeric.setdefault(
                    str(column),
                    {"column_index": columns.index(str(column)), "count": 0, "mean": 0.0, "m2": 0.0, "min": None, "max": None},
                )
                old_count = aggregate["count"]
                batch_count = len(finite_values)
                batch_mean = math.fsum(value / batch_count for value in finite_values)
                try:
                    batch_m2 = math.fsum((value - batch_mean) * (value - batch_mean) for value in finite_values)
                except OverflowError:
                    batch_m2 = math.inf
                total_count = old_count + batch_count
                delta = batch_mean - aggregate["mean"]
                aggregate["mean"] += delta * batch_count / total_count
                aggregate["m2"] += batch_m2 + delta * delta * old_count * batch_count / total_count
                aggregate["count"] = total_count
                batch_min, batch_max = min(finite_values), max(finite_values)
                aggregate["min"] = batch_min if aggregate["min"] is None else min(aggregate["min"], batch_min)
                aggregate["max"] = batch_max if aggregate["max"] is None else max(aggregate["max"], batch_max)

                if exact_distribution:
                    if numeric_db is None:
                        temp_dir = tempfile.mkdtemp(prefix="data-analyzer-stats-")
                        db_path = str(Path(temp_dir) / "values.sqlite3")
                        numeric_db = sqlite3.connect(db_path)
                        numeric_db.execute("PRAGMA journal_mode=OFF")
                        numeric_db.execute("PRAGMA synchronous=OFF")
                        numeric_db.execute("PRAGMA temp_store=FILE")
                        page_size = numeric_db.execute("PRAGMA page_size").fetchone()[0]
                        numeric_db.execute(f"PRAGMA max_page_count={max(16, MAX_STATS_DB_BYTES // page_size)}")
                        numeric_db.execute("CREATE TABLE numeric_values (column_id INTEGER, value REAL, numeric_key TEXT)")
                        numeric_db.execute("CREATE INDEX value_order ON numeric_values(column_id, value)")
                        numeric_db.execute("CREATE INDEX mode_order ON numeric_values(column_id, numeric_key, value)")
                    raw_values = [value for value in values if math.isfinite(float(value))]
                    records = [
                        (columns.index(str(column)), float(value), _numeric_key(value))
                        for value in raw_values
                    ]
                    try:
                        numeric_db.executemany(
                            "INSERT INTO numeric_values(column_id, value, numeric_key) VALUES (?, ?, ?)", records
                        )
                        numeric_db.commit()
                    except sqlite3.DatabaseError:
                        exact_distribution = False
                    if exact_distribution and Path(db_path).stat().st_size > MAX_STATS_DB_BYTES:
                        exact_distribution = False
                    if not exact_distribution:
                        numeric_db.close()
                        numeric_db = None
                        Path(db_path).unlink(missing_ok=True)
                        Path(db_path).parent.rmdir()
                        db_path = None

        if row_count == 0:
            raise ValueError("CSV has no data rows")

        stats: dict[str, dict[str, Any]] = {}
        for column, values in numeric.items():
            count = values["count"]
            minimum, maximum = values["min"], values["max"]
            variance_population = _finite_or_none(values["m2"] / count) if count else None
            variance_sample = _finite_or_none(values["m2"] / (count - 1)) if count > 1 else None
            summary: dict[str, Any] = {
                "count": count,
                "mean": _finite_or_none(values["mean"]),
                "min": minimum,
                "max": maximum,
                "range": _finite_or_none(maximum - minimum),
                "variance_population": variance_population,
                "variance_sample": variance_sample,
                "stddev_population": math.sqrt(variance_population) if variance_population is not None else None,
                "stddev_sample": math.sqrt(variance_sample) if variance_sample is not None else None,
                "median": None,
                "mode": None,
                "mode_frequency": None,
                "mode_tie_count": None,
                "q1": None,
                "q3": None,
                "iqr": None,
                "exact_distribution": exact_distribution,
            }
            if exact_distribution and numeric_db is not None:
                column_id = values["column_index"]
                summary.update(_exact_distribution_stats(numeric_db, column_id, count))
            stats[column] = summary

        return {
            "filename": "uploaded.csv",
            "rows": row_count,
            "columns": columns,
            "missing": missing,
            "numeric_summary": stats,
            "preview": preview,
            "exact_distribution_available": exact_distribution,
            "statistics_note": None if exact_distribution else "Median, modus, dan kuartil dilewati karena batas penyimpanan sementara tercapai.",
        }
    finally:
        if numeric_db is not None:
            numeric_db.close()
        if db_path:
            Path(db_path).unlink(missing_ok=True)
            Path(db_path).parent.rmdir()


def _numeric_key(value: Any) -> str:
    number = Decimal(str(value))
    if number == 0:
        return "0"
    with localcontext() as context:
        context.prec = max(32, len(number.as_tuple().digits) + 2)
        number = number.normalize(context)
    return format(number, "f") if number.adjusted() > -6 and number.adjusted() < 21 else str(number)


def _finite_or_none(value: float) -> float | None:
    return value if math.isfinite(value) else None


def _ordered_value(database: sqlite3.Connection, column_id: int, offset: int) -> float:
    row = database.execute(
        "SELECT value FROM numeric_values WHERE column_id=? ORDER BY value LIMIT 1 OFFSET ?",
        (column_id, offset),
    ).fetchone()
    return float(row[0])


def _quantile(database: sqlite3.Connection, column_id: int, count: int, percentile: float) -> float:
    position = (count - 1) * percentile
    lower, upper = math.floor(position), math.ceil(position)
    low_value = _ordered_value(database, column_id, lower)
    high_value = _ordered_value(database, column_id, upper)
    return low_value + (high_value - low_value) * (position - lower)


def _exact_distribution_stats(database: sqlite3.Connection, column_id: int, count: int) -> dict[str, Any]:
    frequency, tie_count = database.execute(
        "SELECT MAX(n), SUM(CASE WHEN n=(SELECT MAX(n) FROM (SELECT COUNT(*) AS n FROM numeric_values WHERE column_id=? GROUP BY numeric_key)) THEN 1 ELSE 0 END) "
        "FROM (SELECT COUNT(*) AS n FROM numeric_values WHERE column_id=? GROUP BY numeric_key)",
        (column_id, column_id),
    ).fetchone()
    mode_key = None
    mode = None
    if frequency and frequency > 1:
        mode_key = database.execute(
            "SELECT numeric_key FROM numeric_values WHERE column_id=? GROUP BY numeric_key ORDER BY COUNT(*) DESC, MIN(value) ASC LIMIT 1",
            (column_id,),
        ).fetchone()[0]
        decimal_mode = Decimal(mode_key)
        mode = int(decimal_mode) if decimal_mode == decimal_mode.to_integral_value() else float(decimal_mode)
    else:
        tie_count = None
    q1 = _quantile(database, column_id, count, 0.25)
    median = _quantile(database, column_id, count, 0.5)
    q3 = _quantile(database, column_id, count, 0.75)
    return {
        "median": median,
        "mode": mode,
        "mode_frequency": int(frequency) if frequency is not None else None,
        "mode_tie_count": int(tie_count) if tie_count is not None else None,
        "q1": q1,
        "q3": q3,
        "iqr": q3 - q1,
    }


def _preview_value(value: Any) -> Any:
    if isinstance(value, (int, float)) and not math.isfinite(value):
        return None
    if isinstance(value, str) and len(value) > MAX_PREVIEW_CELL_CHARS:
        return value[:MAX_PREVIEW_CELL_CHARS] + "…"
    if value is not None and not isinstance(value, (str, int, float, bool)):
        return str(value)
    return value


def _clean_csv(file_obj, output_path: str, options: dict[str, Any]) -> dict[str, Any]:
    source_columns = _read_header(file_obj)
    requested = options["selected_columns"]
    columns = source_columns if requested is None else requested
    if not columns:
        raise ValueError("At least one column must be selected")
    unknown = set(columns) - set(source_columns)
    if unknown:
        raise ValueError("Unknown columns selected")
    columns = [column for column in source_columns if column in set(columns)]

    input_rows = output_rows = duplicates_removed = missing_removed = 0
    preview: list[dict[str, str]] = []
    dedupe_db = str(Path(output_path).with_suffix(".sqlite3")) if options["remove_duplicates"] else None
    database = sqlite3.connect(dedupe_db) if dedupe_db else None
    if database:
        database.execute("PRAGMA journal_mode=OFF")
        database.execute("PRAGMA synchronous=OFF")
        database.execute("CREATE TABLE seen (row_key TEXT PRIMARY KEY)")

    try:
        file_obj.seek(0)
        with open(output_path, "w", encoding="utf-8", newline="") as output:
            writer = csv.writer(output)
            writer.writerow(columns)
            for chunk in pd.read_csv(
                file_obj,
                chunksize=CHUNK_ROWS,
                encoding="utf-8-sig",
                dtype=str,
                keep_default_na=False,
                usecols=columns,
            ):
                chunk = chunk.loc[:, columns]
                input_rows += len(chunk)
                if options["missing_values"] == "drop_rows":
                    keep = chunk.ne("").all(axis=1)
                    missing_removed += int((~keep).sum())
                    chunk = chunk.loc[keep]
                elif options["missing_values"] == "fill":
                    chunk = chunk.replace("", options["fill_value"])

                for row in chunk.itertuples(index=False, name=None):
                    values = tuple("" if pd.isna(value) else str(value) for value in row)
                    if database:
                        key = json.dumps(values, ensure_ascii=False, separators=(",", ":"))
                        cursor = database.execute("INSERT OR IGNORE INTO seen(row_key) VALUES (?)", (key,))
                        if cursor.rowcount == 0:
                            duplicates_removed += 1
                            continue
                    writer.writerow(values)
                    output_rows += 1
                    if len(preview) < MAX_PREVIEW_ROWS:
                        preview.append({column: _preview_value(value) for column, value in zip(columns, values)})
            if database:
                database.commit()
    finally:
        if database:
            database.close()
        if dedupe_db:
            Path(dedupe_db).unlink(missing_ok=True)

    return {
        "rows_before": input_rows,
        "rows_after": output_rows,
        "duplicates_removed": duplicates_removed,
        "missing_rows_removed": missing_removed,
        "columns": columns,
        "preview": preview,
    }


@app.get("/", include_in_schema=False)
async def index() -> FileResponse:
    return FileResponse(APP_DIR / "static" / "index.html")


app.mount("/static", StaticFiles(directory=APP_DIR / "static"), name="static")
