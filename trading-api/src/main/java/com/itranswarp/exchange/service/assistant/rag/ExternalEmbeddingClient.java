/* 外部嵌入适配器：发送公开知识片段或检索问题，独立隔离索引身份和聊天密钥。 */
package com.itranswarp.exchange.service.assistant.rag;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;

public final class ExternalEmbeddingClient implements EmbeddingClient {
  private final String endpoint, model, key, revision;
  private final Transport transport;

  /** 传输接口允许测试不接触网络或真实凭据。 */
  @FunctionalInterface
  interface Transport {
    /** 请求指定模型，返回原始 JSON 供适配器校验。 */
    JsonNode post(String endpoint, Object body, String key, int timeout) throws Exception;
  }

  /** 构造不联网；不完整配置只在知识索引后台报错。 */
  public ExternalEmbeddingClient(String endpoint, String model, String key, String revision) {
    this(endpoint, model, key, revision, (url, body, secret, timeout) ->
        RagHttp.exchange(url, "POST", body, secret, timeout, 4194304));
  }

  /** 注入传输，验证实际请求和响应处理。 */
  ExternalEmbeddingClient(String endpoint, String model, String key, String revision, Transport transport) {
    this.endpoint = endpoint; this.model = model; this.key = key; this.revision = revision;
    this.transport = transport;
  }

  /** 仅允许管理员指定的 HTTPS 地址；不把凭据加入索引身份。 */
  @Override
  public String fingerprint() {
    var uri = URI.create(endpoint);
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
        uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
        !uri.getPath().endsWith("/embeddings") || model.isBlank() || key.isBlank() || revision.isBlank()) {
      throw new IllegalStateException("EXTERNAL_EMBEDDING_CONFIG_INVALID");
    }
    return "external-codepoints-v1:" + KnowledgeDocumentService.hash(
        endpoint + "\n" + model + "\n" + revision);
  }

  /** 兼容接口没有 tokenizer：按 Unicode 码点保守切片，用字符预算估算，不截断章节。 */
  @Override
  public List<Piece> split(List<KnowledgeDocumentService.Section> sections) {
    var pieces = new ArrayList<Piece>();
    for (var section : sections) {
      String prefix = section.document() + " · " + section.title() + "\n";
      String body = section.body();
      int titleLength = prefix.codePointCount(0, prefix.length());
      int window = 220 - titleLength;
      if (window <= 40) throw new IllegalStateException("EXTERNAL_EMBEDDING_TITLE_TOO_LONG");
      int ordinal = 0;
      for (int start = 0; start < body.length();) {
        int size = Math.min(window, body.codePointCount(start, body.length()));
        int end = body.offsetByCodePoints(start, size);
        pieces.add(new Piece(section.id(), ordinal++, prefix + body.substring(start, end),
            titleLength + size, start, end));
        if (end == body.length()) break;
        start = body.offsetByCodePoints(end, -30);
      }
    }
    return List.copyOf(pieces);
  }

  /** 文档和查询使用同一模型；按 index 还原批次，拒绝缺失、重复或无效向量。 */
  @Override
  public List<List<Double>> embed(List<String> texts, boolean query, int timeoutMillis) throws Exception {
    fingerprint();
    if (texts.isEmpty() || texts.size() > 32 || (query && texts.size() != 1))
      throw new IllegalArgumentException("EXTERNAL_EMBEDDING_BATCH_INVALID");
    var response = transport.post(endpoint, Map.of("model", model, "input", texts), key, timeoutMillis);
    var rows = response.path("data");
    if (!rows.isArray() || rows.size() != texts.size())
      throw new IllegalStateException("EXTERNAL_EMBEDDING_RESPONSE_INVALID");
    var result = new ArrayList<List<Double>>(Collections.nCopies(texts.size(), null));
    int dimensions = -1;
    for (var row : rows) {
      var index = row.path("index");
      int i = index.canConvertToInt() && index.isIntegralNumber() ? index.intValue() : -1;
      var vector = row.path("embedding");
      if (i < 0 || i >= result.size() || result.get(i) != null || !vector.isArray() ||
          vector.isEmpty() || vector.size() > 8192 || (dimensions != -1 && dimensions != vector.size()))
        throw new IllegalStateException("EXTERNAL_EMBEDDING_VECTOR_INVALID");
      dimensions = vector.size();
      var values = new ArrayList<Double>();
      double norm = 0;
      for (var component : vector) {
        double value = component.isNumber() ? component.doubleValue() : Double.NaN;
        if (!Double.isFinite(value)) throw new IllegalStateException("EXTERNAL_EMBEDDING_VECTOR_INVALID");
        values.add(value);
        norm = Math.hypot(norm, value);
      }
      if (norm == 0 || !Double.isFinite(norm)) throw new IllegalStateException("EXTERNAL_EMBEDDING_VECTOR_INVALID");
      double length = norm;
      result.set(i, values.stream().map(value -> value / length).toList());
    }
    return List.copyOf(result);
  }
}
