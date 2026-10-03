from __future__ import annotations

import os
import tempfile
from pathlib import Path
from typing import Any

import pandas as pd
from fastapi import FastAPI, File, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.concurrency import run_in_threadpool

MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_BYTES", str(50 * 1024 * 1024)))
CHUNK_ROWS = int(os.getenv("CSV_CHUNK_ROWS", "10000"))
MAX_COLUMNS = int(os.getenv("MAX_COLUMNS", "500"))
MAX_PREVIEW_ROWS = 100
COPY_BLOCK_BYTES = 1024 * 1024
APP_DIR = Path(__file__).parent

app = FastAPI(title="Data Analyzer", version="0.1.0")
allowed_origins = [origin.strip() for origin in os.getenv("CORS_ORIGINS", "").split(",") if origin.strip()]
if allowed_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=allowed_origins,
        allow_credentials=False,
        allow_methods=["GET", "POST"],
        allow_headers=["Content-Type"],
    )


@app.middleware("http")
async def reject_oversized_requests(request: Request, call_next):
    # Includes multipart framing allowance; endpoint also enforces actual file bytes.
    content_length = request.headers.get("content-length")
    if request.url.path == "/api/analyze" and content_length:
        try:
            too_large = int(content_length) > MAX_UPLOAD_BYTES + 1024 * 1024
        except ValueError:
            return JSONResponse(status_code=400, content={"detail": "Invalid Content-Length header."})
        if too_large:
            return JSONResponse(status_code=413, content={"detail": "Request exceeds the upload size limit."})
    return await call_next(request)


@app.get("/api/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/analyze")
async def analyze(request: Request, file: UploadFile = File(...)) -> dict[str, Any]:
    """Analyze a CSV incrementally; uploaded bytes are capped before parsing."""
    if not file.filename or Path(file.filename).suffix.lower() != ".csv":
        raise HTTPException(status_code=415, detail="Only .csv files are supported.")

    temp_path: str | None = None
    try:
        size = 0
        with tempfile.NamedTemporaryFile(prefix="data-analyzer-", suffix=".csv", delete=False) as temporary:
            temp_path = temporary.name
            while block := await file.read(COPY_BLOCK_BYTES):
                size += len(block)
                if size > MAX_UPLOAD_BYTES:
                    raise HTTPException(status_code=413, detail="File exceeds the upload size limit.")
                temporary.write(block)
        if size == 0:
            raise HTTPException(status_code=400, detail="The uploaded file is empty.")
        try:
            result = await run_in_threadpool(_analyze_csv, temp_path)
        except (UnicodeDecodeError, pd.errors.ParserError, pd.errors.EmptyDataError, ValueError) as exc:
            raise HTTPException(status_code=422, detail="Could not parse this CSV file.") from exc
        return result
    finally:
        await file.close()
        if temp_path:
            Path(temp_path).unlink(missing_ok=True)


def _analyze_csv(path: str) -> dict[str, Any]:
    """Aggregate bounded chunks, retaining only a capped preview and column summaries."""
    header = pd.read_csv(path, nrows=0)
    columns = [str(column) for column in header.columns]
    if not columns or len(columns) > MAX_COLUMNS:
        raise ValueError("Invalid or excessive number of columns")

    row_count = 0
    preview: list[dict[str, Any]] = []
    missing: dict[str, int] = {column: 0 for column in columns}
    numeric: dict[str, dict[str, float | int | None]] = {}

    for chunk in pd.read_csv(path, chunksize=CHUNK_ROWS):
        row_count += len(chunk)
        if len(preview) < MAX_PREVIEW_ROWS:
            preview.extend(chunk.head(MAX_PREVIEW_ROWS - len(preview)).astype(object).where(pd.notna(chunk.head(MAX_PREVIEW_ROWS - len(preview))), None).to_dict(orient="records"))
        for column in columns:
            missing[column] += int(chunk[column].isna().sum())
        for column in chunk.select_dtypes(include="number").columns:
            values = chunk[column].dropna()
            aggregate = numeric.setdefault(str(column), {"count": 0, "sum": 0.0, "min": None, "max": None})
            if not values.empty:
                aggregate["count"] = int(aggregate["count"]) + len(values)
                aggregate["sum"] = float(aggregate["sum"]) + float(values.sum())
                aggregate["min"] = float(values.min()) if aggregate["min"] is None else min(float(aggregate["min"]), float(values.min()))
                aggregate["max"] = float(values.max()) if aggregate["max"] is None else max(float(aggregate["max"]), float(values.max()))

    if row_count == 0:
        raise ValueError("CSV has no data rows")
    stats = {
        column: {
            "count": values["count"],
            "mean": values["sum"] / values["count"] if values["count"] else None,
            "min": values["min"],
            "max": values["max"],
        }
        for column, values in numeric.items()
    }
    return {"filename": "uploaded.csv", "rows": row_count, "columns": columns, "missing": missing, "numeric_summary": stats, "preview": preview}


@app.get("/", include_in_schema=False)
async def index() -> FileResponse:
    return FileResponse(APP_DIR / "static" / "index.html")


app.mount("/static", StaticFiles(directory=APP_DIR / "static"), name="static")
