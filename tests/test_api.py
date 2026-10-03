from fastapi.testclient import TestClient

import app.main as main
from app.main import app

client = TestClient(app)


def test_health():
    response = client.get("/api/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["x-frame-options"] == "DENY"


def test_analyze_csv_returns_chunked_summary_and_preview():
    response = client.post(
        "/api/analyze",
        files={"file": ("sample.csv", "value,label\n1,a\n2,b\n,b\n", "text/csv")},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["rows"] == 3
    assert data["columns"] == ["value", "label"]
    assert data["missing"]["value"] == 1
    assert data["numeric_summary"]["value"] == {"count": 2, "mean": 1.5, "min": 1.0, "max": 2.0}
    assert len(data["preview"]) == 3


def test_rejects_non_csv_upload():
    response = client.post("/api/analyze", files={"file": ("data.xlsx", b"not a spreadsheet", "application/octet-stream")})
    assert response.status_code == 415


def test_rejects_empty_csv():
    response = client.post("/api/analyze", files={"file": ("empty.csv", b"", "text/csv")})
    assert response.status_code == 400


def test_rejects_malformed_csv():
    response = client.post("/api/analyze", files={"file": ("bad.csv", b'"unterminated', "text/csv")})
    assert response.status_code == 422


def test_aggregates_across_multiple_chunks(monkeypatch):
    monkeypatch.setattr(main, "CHUNK_ROWS", 2)
    response = client.post(
        "/api/analyze",
        files={"file": ("many.csv", "amount\n1\n2\n3\n4\n5\n", "text/csv")},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["rows"] == 5
    assert data["numeric_summary"]["amount"] == {"count": 5, "mean": 3.0, "min": 1.0, "max": 5.0}


def test_rejects_upload_over_configured_limit(monkeypatch):
    monkeypatch.setattr(main, "MAX_UPLOAD_BYTES", 4)
    response = client.post(
        "/api/analyze",
        files={"file": ("large.csv", "value\n12345\n", "text/csv")},
    )
    assert response.status_code == 413
    assert response.json() == {"detail": "File exceeds the upload size limit."}


def test_rejects_oversized_multipart_request_before_parsing(monkeypatch):
    monkeypatch.setattr(main, "MAX_UPLOAD_BYTES", 4)
    response = client.post(
        "/api/analyze",
        headers={"content-length": str(2 * 1024 * 1024)},
        content=b"ignored",
    )
    assert response.status_code == 413
    assert response.json() == {"detail": "Request exceeds the upload size limit."}


def test_clean_preview_selects_columns_drops_missing_and_cross_chunk_duplicates(monkeypatch):
    monkeypatch.setattr(main, "CHUNK_ROWS", 2)
    response = client.post(
        "/api/clean/preview",
        data={
            "selected_columns": '["category"]',
            "missing_values": "drop_rows",
            "remove_duplicates": "true",
        },
        files={"file": ("sample.csv", "id,category\n1,a\n2,a\n3,\n4,c\n", "text/csv")},
    )
    assert response.status_code == 200
    assert response.json() == {
        "rows_before": 4,
        "rows_after": 2,
        "duplicates_removed": 1,
        "missing_rows_removed": 1,
        "columns": ["category"],
        "preview": [{"category": "a"}, {"category": "c"}],
    }


def test_clean_download_fills_missing_values_and_returns_csv():
    response = client.post(
        "/api/clean/download",
        data={"selected_columns": '["name", "value"]', "missing_values": "fill", "fill_value": "unknown"},
        files={"file": ("sample.csv", "name,value\nAda,\n,5\n", "text/csv")},
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/csv")
    assert response.content.decode("utf-8") == "name,value\r\nAda,unknown\r\nunknown,5\r\n"


def test_clean_rejects_empty_column_selection():
    response = client.post(
        "/api/clean/preview",
        data={"selected_columns": "[]"},
        files={"file": ("sample.csv", "a,b\n1,2\n", "text/csv")},
    )
    assert response.status_code == 422


def test_non_finite_numeric_values_do_not_break_json_response():
    response = client.post(
        "/api/analyze",
        files={"file": ("nonfinite.csv", "value\n1\ninf\n3\n", "text/csv")},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["missing"]["value"] == 1
    assert data["numeric_summary"]["value"] == {"count": 2, "mean": 2.0, "min": 1.0, "max": 3.0}


def test_clean_download_removes_temporary_output_after_response(monkeypatch, tmp_path):
    monkeypatch.setattr(main.tempfile, "tempdir", str(tmp_path))
    response = client.post(
        "/api/clean/download",
        data={"selected_columns": "null", "missing_values": "keep"},
        files={"file": ("sample.csv", "a\n1\n", "text/csv")},
    )
    assert response.status_code == 200
    assert list(tmp_path.iterdir()) == []


def test_busy_analyzer_returns_retryable_error(monkeypatch):
    monkeypatch.setattr(main, "job_slots", __import__("asyncio").Semaphore(0))
    monkeypatch.setattr(main, "JOB_WAIT_SECONDS", 0.001)
    response = client.post("/api/analyze", files={"file": ("small.csv", "a\n1\n", "text/csv")})
    assert response.status_code == 503
    assert response.json() == {"detail": "Analyzer is busy. Please retry shortly."}
