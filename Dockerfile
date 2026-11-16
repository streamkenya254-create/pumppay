FROM python:3.10-slim

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy models and scripts
COPY models/ ./models/
COPY scripts/ ./scripts/

ENV PORT=5055
EXPOSE 5055

CMD ["python3", "scripts/ml_service.py"]
