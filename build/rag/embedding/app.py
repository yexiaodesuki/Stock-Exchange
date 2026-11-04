"""有界本地嵌入 HTTP 服务：只提供分片、向量及健康信息，不访问交易数据、不提供重排序。"""
import bisect
import json
import logging
import math
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from model_loader import MODEL_ID, REVISION, PREPROCESS, QUERY_INSTRUCTION, load_model

MODEL = None
STATE = "LOADING"
SLOTS = threading.BoundedSemaphore(2)
FINGERPRINT = MODEL_ID + "@" + REVISION + ":" + PREPROCESS


def initialize():
    """后台加载并有界间隔重试；服务可报告未就绪，不阻塞核心交易容器。"""
    global MODEL, STATE
    while MODEL is None:
        try:
            MODEL = load_model()
            STATE = "READY"
        except Exception:
            STATE = "MODEL_UNAVAILABLE"
            logging.warning("MODEL_UNAVAILABLE: check fixed model cache or download connectivity")
            time.sleep(15)


def split_text(tokenizer, title, text, maximum=300, overlap=40):
    """按真实 token 偏移切片，优先句段边界；每片重复标题，保留原始字符而非解码后重写。"""
    prefix = title.strip() + "\n"
    reserve = len(tokenizer(prefix, add_special_tokens=False)["input_ids"]) + 2
    budget = maximum - reserve
    if budget <= overlap or len(text) > 16000:
        raise ValueError("CHUNK_INPUT_INVALID")
    offsets = tokenizer(text, add_special_tokens=False, return_offsets_mapping=True)["offset_mapping"]
    chunks, start = [], 0
    while start < len(offsets):
        end = min(start + budget, len(offsets))
        begin_char, end_char = offsets[start][0], offsets[end - 1][1]
        if end < len(offsets):
            boundary = max(text.rfind(mark, begin_char + (end_char - begin_char) // 2, end_char) for mark in "。！？；\n")
            if boundary >= 0:
                ends = [p[1] for p in offsets]
                end = max(start + 1, bisect.bisect_right(ends, boundary + 1))
                end_char = offsets[end - 1][1]
        raw = text[begin_char:end_char]
        actual_start = begin_char + len(raw) - len(raw.lstrip())
        actual_end = begin_char + len(raw.rstrip())
        value = prefix + raw.strip()
        count = len(tokenizer(value, add_special_tokens=True)["input_ids"])
        if count > maximum:
            raise ValueError("CHUNK_TOKEN_LIMIT")
        # Java使用UTF16偏移，不能直接把Python码点偏移用于含emoji的原文。
        chunks.append({"text": value, "tokens": count,
            "start": len(text[:actual_start].encode("utf-16-le")) // 2,
            "end": len(text[:actual_end].encode("utf-16-le")) // 2})
        if end == len(offsets):
            break
        start = max(start + 1, end - overlap)
    return chunks


def process(path, body, model):
    """请求只能包含文本；限制批次、token和向量维度，禁止让模型静默截断入库文本。"""
    if path == "/v1/chunks":
        items = body.get("items")
        if not isinstance(items, list) or not 1 <= len(items) <= 32:
            raise ValueError("CHUNK_BATCH_INVALID")
        result = []
        for item in items:
            if not isinstance(item, dict) or not all(isinstance(item.get(k), str) for k in ("id", "title", "text")) or len(item["title"]) > 160:
                raise ValueError("CHUNK_INPUT_INVALID")
            for index, chunk in enumerate(split_text(model.tokenizer, item["title"], item["text"])):
                result.append({"parentId": item["id"], "ordinal": index, **chunk})
        if len(result) > 256:
            raise ValueError("CHUNK_COUNT_LIMIT")
        return {"fingerprint": FINGERPRINT, "chunks": result}
    if path != "/v1/embeddings":
        raise LookupError("NOT_FOUND")
    texts, kind = body.get("texts"), body.get("kind")
    if kind not in ("query", "document") or not isinstance(texts, list) or not 1 <= len(texts) <= 32:
        raise ValueError("EMBEDDING_BATCH_INVALID")
    if kind == "query" and len(texts) != 1:
        raise ValueError("QUERY_BATCH_INVALID")
    if any(not isinstance(t, str) or not t.strip() or len(t) > 4000 for t in texts):
        raise ValueError("EMBEDDING_TEXT_INVALID")
    values = [QUERY_INSTRUCTION + t if kind == "query" else t for t in texts]
    if any(len(model.tokenizer(t, add_special_tokens=True)["input_ids"]) > 512 for t in values):
        raise ValueError("EMBEDDING_TOKEN_LIMIT")
    vectors = model.encode(values, normalize_embeddings=True, batch_size=8, show_progress_bar=False).tolist()
    if any(len(v) != 512 or any(not math.isfinite(n) for n in v) for v in vectors):
        raise RuntimeError("EMBEDDING_VECTOR_INVALID")
    return {"fingerprint": FINGERPRINT, "dimension": 512, "embeddings": vectors}


class Handler(BaseHTTPRequestHandler):
    """不记录用户原文，不接受任意路径或 URL，响应只含固定错误码。"""

    def setup(self):
        """限制慢请求占用连接；核心服务不经过本推理服务。"""
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, format, *args):
        """不把提问、正文和访问路径写入默认 HTTP 访问日志。"""
        return

    def reply(self, code, value):
        """仅输出 UTF-8 JSON，不把异常正文或本地路径暴露给调用者。"""
        raw = json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        """健康检查包含模型自检结果；文件存在或进程存活不代表可以推理。"""
        if self.path not in ("/health", "/info"):
            self.reply(404, {"code": "NOT_FOUND"})
            return
        self.reply(200 if MODEL is not None else 503, {"state": STATE, "model": MODEL_ID,
            "revision": REVISION, "fingerprint": FINGERPRINT, "dimension": 512, "maxTokens": 512})

    def do_POST(self):
        """文本请求总量和并发受限，未加载或繁忙时立即拒绝，不积压无限推理任务。"""
        if self.headers.get("Origin"):
            self.reply(403, {"code": "BROWSER_ACCESS_DENIED"})
            return
        if MODEL is None:
            self.reply(503, {"code": "MODEL_UNAVAILABLE"})
            return
        if not SLOTS.acquire(blocking=False):
            self.reply(429, {"code": "EMBEDDING_BUSY"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 131072:
                self.reply(413, {"code": "BODY_LIMIT"})
                return
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ValueError("JSON_OBJECT_REQUIRED")
            self.reply(200, process(self.path, body, MODEL))
        except (ValueError, TypeError, KeyError):
            self.reply(400, {"code": "REQUEST_INVALID"})
        except LookupError:
            self.reply(404, {"code": "NOT_FOUND"})
        except Exception:
            self.reply(503, {"code": "EMBEDDING_UNAVAILABLE"})
        finally:
            SLOTS.release()


if __name__ == "__main__":
    logging.basicConfig(level=logging.WARNING)
    threading.Thread(target=initialize, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", 8081), Handler).serve_forever()
