ARG BASE_PLATFORM=linux/amd64
FROM --platform=${BASE_PLATFORM} python:3.13-slim

ENV PYTHONUNBUFFERED=1

WORKDIR /app
COPY hf-fixture-server.py /app/hf-fixture-server.py

EXPOSE 8765

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=6 \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/health', timeout=2).read()"

ENTRYPOINT ["python3", "/app/hf-fixture-server.py"]
CMD ["--host", "0.0.0.0", "--port", "8765"]
