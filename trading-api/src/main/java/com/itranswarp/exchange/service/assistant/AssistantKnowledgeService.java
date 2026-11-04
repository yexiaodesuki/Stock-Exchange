/* 知识问答用例：向量与关键词召回后依据公开证据生成；任何依赖失败降级原文，不触碰交易状态。 */
package com.itranswarp.exchange.service.assistant;

import com.itranswarp.exchange.service.DepositPolicy;
import com.itranswarp.exchange.service.assistant.rag.*;
import java.util.*;

public final class AssistantKnowledgeService {
  public record Source(String title,String section,String version,String excerpt){}
  private final KnowledgeDocumentService documents;
  private final KnowledgeIndexService index;
  private final KnowledgeRetrievalService retrieval;
  private final KnowledgeAnswerClient generation;
  private final int limit;
  /** 兼容原文模式及既有测试，不连接外部依赖。 */
  public AssistantKnowledgeService(DepositPolicy policy,int limit){
    this(new KnowledgeDocumentService(policy),null,null,null,limit);
  }
  /** 生产完整装配；允许关闭RAG，原有知识和交易功能仍可使用。 */
  public AssistantKnowledgeService(KnowledgeDocumentService documents,KnowledgeIndexService index,KnowledgeRetrievalService retrieval,KnowledgeAnswerClient generation,int limit){if(limit<1||limit>3)throw new IllegalArgumentException("知识条数需在1至3之间");this.documents=documents;this.index=index;this.retrieval=retrieval;this.generation=generation;this.limit=limit;}
  /** 依赖状态只影响知识问答，前端不得以其故障判断交易不可用。 */
  public Object status(){return index==null?Map.of("state","DISABLED","chunks",0,"reranking",false):index.status();}
  /** 生成只接收公开证据，不包含调用者ID、余额、订单或入金申请；降级必须明确标识。 */
  public Map<String,Object> read(String question){
    if(retrieval==null)return lexical(question,"RAG_DISABLED");
    List<KnowledgeDocumentService.Chunk> chunks;
    try{chunks=retrieval.retrieve(question);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return lexical(question,"RAG_INTERRUPTED");}catch(Exception unavailable){return lexical(question,"RAG_RETRIEVAL_UNAVAILABLE");}
    if(chunks.isEmpty())return facts(List.of(),"RAG_NO_EVIDENCE","KNOWLEDGE_NOT_FOUND","");
    var evidence=new ArrayList<KnowledgeAnswerClient.Evidence>();for(int i=0;i<chunks.size();i++){var c=chunks.get(i);evidence.add(new KnowledgeAnswerClient.Evidence("K"+(i+1),c.section().document(),c.section().title(),documents.expand(c.text())));}
    var sources=evidence.stream().map(e->Map.of("id",e.id(),"title",e.title(),"section",e.section(),"version",chunks.get(Integer.parseInt(e.id().substring(1))-1).section().version(),"excerpt",e.text())).toList();
    if(generation==null||!generation.configured())return facts(sources,"RAG_EXTRACTIVE","GENERATION_NOT_CONFIGURED","");
    try{var answer=generation.answer(question,evidence);if(!answer.answerable())return facts(List.of(),"RAG_NO_EVIDENCE","KNOWLEDGE_NOT_FOUND","");return facts(sources.stream().filter(s->answer.citations().contains(s.get("id"))).toList(),"RAG_GENERATED","",answer.text());}
    catch(InterruptedException interrupted){Thread.currentThread().interrupt();return facts(sources,"RAG_EXTRACTIVE_FALLBACK","RAG_INTERRUPTED","");}catch(Exception unavailable){return facts(sources,"RAG_EXTRACTIVE_FALLBACK","RAG_GENERATION_UNAVAILABLE","");}
  }
  /** 回退沿用当前JAR中的原文，不能在索引尚未就绪时展示旧版本向量库内容。 */
  private Map<String,Object> lexical(String question,String reason){var sources=documents.lexical(question,limit).stream().map(s->new Source(s.document(),s.title(),s.version(),documents.expand(s.body()))).toList();return facts(sources,"LEXICAL_FALLBACK",reason,"");}
  /** 保留原sources协议，新增模式、原因及生成答案；无证据时不发送生成请求。 */
  private Map<String,Object> facts(List<?> sources,String mode,String reason,String answer){
    return Map.of("source","项目公开知识（无重排序）","found",!sources.isEmpty(),"sources",sources,"ragMode",mode,"ragReason",reason,"answer",answer,"ragStatus",status());
  }
}
