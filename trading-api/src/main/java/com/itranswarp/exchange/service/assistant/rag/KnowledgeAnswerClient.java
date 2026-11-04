/* 证据回答端口：只接收公开知识片段，不能读取账户、调用工具或执行交易。 */
package com.itranswarp.exchange.service.assistant.rag;
import java.util.List;

public interface KnowledgeAnswerClient {
  record Evidence(String id,String title,String section,String text){}
  record Answer(boolean answerable,String text,List<String> citations){}
  /** 未启用时只展示检索原文，不发收费请求。 */
  boolean configured();
  /** 输出必须引用本次证据；无法回答返回明确拒答，传输失败由调用者退回原文。 */
  Answer answer(String question,List<Evidence> evidence)throws Exception;
}
