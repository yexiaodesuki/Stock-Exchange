"""固定版本中文嵌入模型：优先只读缓存，缺失则下载到独立卷；不加载远程自定义代码。"""
import os
from pathlib import Path

MODEL_ID = "BAAI/bge-small-zh-v1.5"
REVISION = "7999e1d3359715c523056ef9478215996d62a620"
PREPROCESS = "bge-zh-query-instruction-normalized-v1"
QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："


def complete(path):
    """检查本实现使用的 safetensors、分词器及池化配置，不能仅凭缓存文件夹存在判断完整。"""
    required = ("config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json",
                "vocab.txt", "modules.json", "1_Pooling/config.json")
    return all((path / name).is_file() and (path / name).stat().st_size > 0 for name in required)


def load_model():
    """固定模型与提交版本，下载只写自己的缓存卷；离线模式缺失时失败而非更换模型。"""
    from sentence_transformers import SentenceTransformer
    import torch
    source = Path(os.environ.get("MODEL_SOURCE", "/models-source")) / "models--BAAI--bge-small-zh-v1.5" / "snapshots" / REVISION
    if not complete(source):
        from huggingface_hub import snapshot_download
        source = Path(snapshot_download(MODEL_ID, revision=REVISION,
            cache_dir=os.environ.get("MODEL_CACHE", "/models/cache"),
            local_files_only=os.environ.get("MODEL_DOWNLOAD", "true").lower() != "true",
            allow_patterns=["*.json", "vocab.txt", "model.safetensors", "1_Pooling/*"]))
    if not complete(source):
        raise RuntimeError("MODEL_FILES_INCOMPLETE")
    torch.set_num_threads(2)
    model = SentenceTransformer(str(source), device="cpu", local_files_only=True, trust_remote_code=False)
    model.max_seq_length = 512
    probe = model.encode(["嵌入就绪检查"], normalize_embeddings=True, show_progress_bar=False)
    if probe.shape != (1, 512):
        raise RuntimeError("MODEL_DIMENSION_INVALID")
    return model
