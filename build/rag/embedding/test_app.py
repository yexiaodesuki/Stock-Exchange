"""嵌入服务纯逻辑回归：假分词器和向量覆盖请求边界，不下载模型、不访问交易系统。"""
import unittest
import app


class Tokenizer:
    """按字符产生可检查偏移，验证切片覆盖和重叠，不假称与真实BGE分词等价。"""

    def __call__(self, text, add_special_tokens=False, return_offsets_mapping=False):
        """构造确定性token与偏移，用于测试原文覆盖。"""
        return {"input_ids": [1] * (len(text) + (2 if add_special_tokens else 0)),
                "offset_mapping": [(i, i + 1) for i in range(len(text))]}


class Vectors(list):
    """只模拟numpy接口，不让服务测试依赖真实推理。"""

    def tolist(self):
        """原样返回可JSON序列化数组。"""
        return list(self)


class Model:
    """记录输入，确保查询指令不被错误加到文档。"""
    tokenizer = Tokenizer()

    def encode(self, values, **kwargs):
        """归一化单位向量用于验证接口形状，不能作为检索质量测试。"""
        self.values = values
        return Vectors([[1.0] + [0.0] * 511 for value in values])


class ServiceTest(unittest.TestCase):
    """请求和切片回归，不启动HTTP监听或外部服务。"""

    def test_query_and_document_preprocessing(self):
        """仅查询添加固定指令，响应包含512维和完整模型身份。"""
        model = Model()
        value = app.process("/v1/embeddings", {"texts": ["如何充值"], "kind": "query"}, model)
        self.assertTrue(model.values[0].startswith(app.QUERY_INSTRUCTION))
        self.assertEqual(512, len(value["embeddings"][0]))
        app.process("/v1/embeddings", {"texts": ["公开文档"], "kind": "document"}, model)
        self.assertEqual(["公开文档"], model.values)

    def test_limits_never_silently_truncate(self):
        """过长、空批次、类型错误明确拒绝，不让transformers静默截断。"""
        for body in ({"texts": ["字" * 513], "kind": "document"}, {"texts": [], "kind": "query"}, {"texts": [1], "kind": "query"}, {"texts": ["a", "b"], "kind": "query"}):
            with self.assertRaises(ValueError):
                app.process("/v1/embeddings", body, Model())

    def test_chunks_cover_original_and_preserve_title(self):
        """长章节分片后无正文缺口，每片带标题且不超过300token。"""
        text = "买单冻结资金。" * 100
        chunks = app.split_text(Tokenizer(), "交易规则", text)
        self.assertGreater(len(chunks), 1)
        covered, previous = 0, -1
        for chunk in chunks:
            self.assertLessEqual(chunk["tokens"], 300)
            self.assertTrue(chunk["text"].startswith("交易规则\n"))
            raw = chunk["text"].split("\n", 1)[1]
            start = chunk["start"]
            self.assertEqual(raw, text[start:chunk["end"]])
            self.assertLessEqual(start, covered)
            covered = max(covered, start + len(raw))
            previous = start
        self.assertEqual(len(text), covered)


if __name__ == "__main__":
    unittest.main()
