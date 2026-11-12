/* 外部嵌入契约测试：仅使用模拟响应，不联网、不读取真实密钥。 */
package com.itranswarp.exchange.service.assistant.rag;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalEmbeddingClientTest {
  /** 检查请求协议、索引重排和向量归一化。 */
  @Test void embedsUsingProviderProtocol() throws Exception {
    var client = new ExternalEmbeddingClient("https://test.example/v1/embeddings", "sample", "fake", "1",
        (url, body, key, timeout) -> {
          assertEquals(Map.of("model", "sample", "input", List.of("甲", "乙")), body);
          assertEquals("fake", key);
          assertEquals(1500, timeout);
          return RagHttp.JSON.readTree("""
              {"data":[{"index":1,"embedding":[0,2]},{"index":0,"embedding":[3,4]}]}
              """);
        });
    assertEquals(List.of(List.of(0.6, 0.8), List.of(0.0, 1.0)),
        client.embed(List.of("甲", "乙"), false, 1500));
  }

  /** 拒绝重复批次索引、不安全地址，并验证模型修订隔离。 */
  @Test void rejectsInvalidConfigurationAndResponses() {
    String endpoint = "https://test.example/v1/embeddings";
    var client = new ExternalEmbeddingClient(endpoint, "sample", "fake", "1",
        (url, body, key, timeout) -> RagHttp.JSON.readTree("""
            {"data":[{"index":0,"embedding":[1]},{"index":0,"embedding":[1]}]}
            """));
    assertThrows(IllegalStateException.class, () -> client.embed(List.of("甲", "乙"), false, 1500));
    assertThrows(IllegalStateException.class,
        new ExternalEmbeddingClient("http://test.example/v1/embeddings", "sample", "fake", "1")::fingerprint);
    assertNotEquals(client.fingerprint(), new ExternalEmbeddingClient(endpoint, "sample", "fake", "2").fingerprint());
    assertEquals(client.fingerprint(), new ExternalEmbeddingClient(endpoint, "sample", "rotated", "1").fingerprint());
  }

  /** 验证长章节完整覆盖且 Unicode 字符不会被切成半个代理项。 */
  @Test void preservesOriginalSections() {
    var client = new ExternalEmbeddingClient("", "", "", "1");
    var section = new KnowledgeDocumentService.Section("id", "文档", "标题", List.of("主题"), "股📈".repeat(180), "1");
    var documents = new KnowledgeDocumentService(null, List.of(section));
    var pieces = client.split(List.of(section));
    assertTrue(documents.chunks(pieces).size() > 1);
    assertEquals(section.body().length(), pieces.get(pieces.size() - 1).end());
    assertTrue(pieces.stream().noneMatch(p -> Character.isHighSurrogate(p.text().charAt(p.text().length() - 1))));
  }
}
