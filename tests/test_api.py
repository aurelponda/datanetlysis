from fastapi.testclient import TestClient

import app.main as main
from app.main import app

client = TestClient(app)


def test_health():
    response = client.get("/api/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


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
