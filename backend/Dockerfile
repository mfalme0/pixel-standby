FROM python:3.12-slim

WORKDIR /app

# Runtime dependencies only.
RUN pip install --no-cache-dir \
    "fastapi>=0.115" \
    "uvicorn[standard]>=0.32" \
    "httpx>=0.27"

COPY app ./app
COPY pyproject.toml ./

EXPOSE 8000

ENV PIXEL_BEARER_TOKEN="" \
    UPSTREAM_BASE_URL="https://query1.finance.yahoo.com" \
    CACHE_TTL_SECONDS="60" \
    UPSTREAM_RATE_CAPACITY="30" \
    UPSTREAM_RATE_WINDOW_SECONDS="60" \
    LOG_LEVEL="INFO"

CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]