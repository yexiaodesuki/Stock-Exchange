/* 本地嵌入端口：分片和向量必须来自同一固定模型，测试可注入假实现而不下载模型。 */
package com.itranswarp.exchange.service.assistant.rag;
import java.util.List;

public interface EmbeddingClient {
  record Piece(String parentId,int ordinal,String text,int tokens,int start,int end){}
  /** 完整模型身份包含权重提交和查询预处理版本。 */
  String fingerprint()throws Exception;
  /** 使用真实tokenizer切片，不能让入库向量静默丢失尾部规则。 */
  List<Piece> split(List<KnowledgeDocumentService.Section> sections)throws Exception;
  /** 文档和查询走同一个模型；query决定是否添加BGE查询指令。 */
  List<List<Double>> embed(List<String> texts,boolean query,int timeoutMillis)throws Exception;
}
