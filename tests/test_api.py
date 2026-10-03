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
    summary = data["numeric_summary"]["value"]
    assert {key: summary[key] for key in ("count", "mean", "min", "max", "median")} == {
        "count": 2, "mean": 1.5, "min": 1.0, "max": 2.0, "median": 1.5
    }
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
    summary = data["numeric_summary"]["amount"]
    assert {key: summary[key] for key in ("count", "mean", "min", "max", "median")} == {
        "count": 5, "mean": 3.0, "min": 1.0, "max": 5.0, "median": 3.0
    }


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
    summary = data["numeric_summary"]["value"]
    assert {key: summary[key] for key in ("count", "mean", "min", "max", "median")} == {
        "count": 2, "mean": 2.0, "min": 1.0, "max": 3.0, "median": 2.0
    }


def test_calculates_center_and_spread_statistics():
    response = client.post(
        "/api/analyze",
        files={"file": ("stats.csv", "value\n1\n2\n2\n4\n", "text/csv")},
    )
    assert response.status_code == 200
    summary = response.json()["numeric_summary"]["value"]
    assert summary["count"] == 4
    assert summary["mean"] == 2.25
    assert summary["median"] == 2.0
    assert summary["mode"] == 2
    assert summary["mode_frequency"] == 2
    assert summary["mode_tie_count"] == 1
    assert summary["min"] == 1.0 and summary["max"] == 4.0 and summary["range"] == 3.0
    assert summary["variance_population"] == 1.1875
    assert summary["variance_sample"] == 1.5833333333333333
    assert summary["q1"] == 1.75 and summary["q3"] == 2.5 and summary["iqr"] == 0.75
    assert summary["exact_distribution"] is True


def test_distribution_statistics_fall_back_when_disk_budget_is_exceeded(monkeypatch):
    monkeypatch.setattr(main, "MAX_STATS_DB_BYTES", 1)
    response = client.post(
        "/api/analyze",
        files={"file": ("stats.csv", "value\n1\n2\n3\n", "text/csv")},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["numeric_summary"]["value"]["mean"] == 2.0
    assert data["numeric_summary"]["value"]["median"] is None
    assert data["numeric_summary"]["value"]["exact_distribution"] is False
    assert data["exact_distribution_available"] is False
    assert data["statistics_note"]


def test_extreme_finite_values_do_not_emit_non_json_infinities():
    response = client.post(
        "/api/analyze",
        files={"file": ("extreme.csv", "value\n-1e308\n1e308\n", "text/csv")},
    )
    assert response.status_code == 200
    summary = response.json()["numeric_summary"]["value"]
    assert summary["median"] == 0.0
    assert summary["range"] is None
    assert summary["iqr"] == 1e308


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
