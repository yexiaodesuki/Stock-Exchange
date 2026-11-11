/* 本机嵌入适配器：固定512维模型及预处理，拒绝非有限向量、部分批次和未知响应。 */
package com.itranswarp.exchange.service.assistant.rag;
import java.util.*;

public final class HttpEmbeddingClient implements EmbeddingClient {
  public static final String FINGERPRINT="BAAI/bge-small-zh-v1.5@7999e1d3359715c523056ef9478215996d62a620:bge-zh-query-instruction-normalized-v1";
  private final String base;
  /** 仅连接明确配置的本机嵌入服务，不接收用户URL。 */
  public HttpEmbeddingClient(String base){this.base=RagHttp.local(base);}
  /** 服务必须实际加载正确模型后才能开始构建索引。 */
  public String fingerprint()throws Exception{var info=RagHttp.exchange(base+"/info","GET",null,null,1500,4096);if(!"READY".equals(info.path("state").asText())||info.path("dimension").asInt()!=512||!FINGERPRINT.equals(info.path("fingerprint").asText()))throw new IllegalStateException("EMBEDDING_IDENTITY_MISMATCH");return FINGERPRINT;}
  /** 响应只允许已有章节编号，有界片数和token；真正内容校验在文档服务完成。 */
  public List<Piece> split(List<KnowledgeDocumentService.Section> sections)throws Exception{
    var items=sections.stream().map(s->Map.of("id",s.id(),"title",s.document()+" · "+s.title(),"text",s.body())).toList();var response=RagHttp.exchange(base+"/v1/chunks","POST",Map.of("items",items),null,5000,524288);
    if(!FINGERPRINT.equals(response.path("fingerprint").asText())||!response.path("chunks").isArray()||response.path("chunks").size()>256)throw new IllegalStateException("CHUNKS_INVALID");
    var values=new ArrayList<Piece>();for(var row:response.path("chunks"))values.add(new Piece(row.path("parentId").asText(),row.path("ordinal").asInt(-1),row.path("text").asText(),row.path("tokens").asInt(),row.path("start").asInt(-1),row.path("end").asInt(-1)));return List.copyOf(values);
  }
  /** 批次完整和归一化向量先验证，避免把另一个模型的结果存入当前集合。 */
  public List<List<Double>> embed(List<String> texts,boolean query,int timeoutMillis)throws Exception{
    if(texts.isEmpty()||texts.size()>32||(query&&texts.size()!=1))throw new IllegalArgumentException("嵌入批次无效");
    var response=RagHttp.exchange(base+"/v1/embeddings","POST",Map.of("texts",texts,"kind",query?"query":"document"),null,timeoutMillis,1048576);var rows=response.path("embeddings");
    if(!FINGERPRINT.equals(response.path("fingerprint").asText())||response.path("dimension").asInt()!=512||!rows.isArray()||rows.size()!=texts.size())throw new IllegalStateException("EMBEDDINGS_INVALID");
    var values=new ArrayList<List<Double>>();for(var row:rows){if(!row.isArray()||row.size()!=512)throw new IllegalStateException("EMBEDDING_DIMENSION");var vector=new ArrayList<Double>();double norm=0;for(var n:row){double v=n.isNumber()?n.asDouble(Double.NaN):Double.NaN;if(!Double.isFinite(v))throw new IllegalStateException("EMBEDDING_NON_FINITE");vector.add(v);norm+=v*v;}if(Math.abs(norm-1)>0.02)throw new IllegalStateException("EMBEDDING_NOT_NORMALIZED");values.add(List.copyOf(vector));}return List.copyOf(values);
  }
}
