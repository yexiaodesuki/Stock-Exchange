# 独立 CPU 嵌入镜像：模型通过只读缓存或固定版本下载加载，权重不进入源码和镜像层。
FROM python:3.11.11-slim-bookworm
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 TOKENIZERS_PARALLELISM=false HF_HUB_DISABLE_TELEMETRY=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --retries 3 -r requirements.txt
COPY model_loader.py app.py ./
EXPOSE 8081
CMD ["python", "app.py"]
