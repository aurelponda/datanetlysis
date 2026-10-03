FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY pyproject.toml README.md ./
COPY app ./app
RUN pip install --no-cache-dir . && useradd --create-home --uid 10001 appuser && mkdir -p /tmp/data-analyzer && chown appuser:appuser /tmp/data-analyzer
USER appuser
ENV TMPDIR=/tmp/data-analyzer
EXPOSE 8000
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000} --workers 1"]
