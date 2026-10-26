# One image for api, worker and scheduler. Build context: repository root.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONPATH=/srv:/srv/backend

WORKDIR /srv
COPY backend/requirements.txt /srv/backend/requirements.txt
RUN pip install -r /srv/backend/requirements.txt

COPY backend/app /srv/backend/app
COPY agent /srv/agent
COPY workers /srv/workers
COPY database /srv/database

RUN useradd --uid 10001 --create-home brain && mkdir -p /data && chown brain /data
USER brain

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
