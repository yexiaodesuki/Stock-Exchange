/* 真实本机嵌入与Chroma验收：仅随机公开知识集合，生成器为假模型；不会调用收费接口或写交易库。 */
package com.itranswarp.exchange.service.assistant.rag;
import static org.junit.jupiter.api.Assertions.*;
import com.itranswarp.exchange.repository.ChromaKnowledgeRepository;
import com.itranswarp.exchange.service.assistant.AssistantKnowledgeService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="SECURITIES_RAG_LIVE_TESTS",matches="true")
class RagLiveTest {
  /** 仅连接本机固定或测试指定端口，不允许将测试请求发送到任意远程服务。 */
  private String endpoint(String key,String defaultValue){return RagHttp.local(System.getenv().getOrDefault(key,defaultValue));}
  /** 随机章节身份隔离集合，正文保持项目真实规则，评测不污染正式知识代次。 */
  private KnowledgeDocumentService documents(String marker){var original=new KnowledgeDocumentService(RagLogicTest.policy());return new KnowledgeDocumentService(RagLogicTest.policy(),original.sections().stream().map(s->new KnowledgeDocumentService.Section(s.id()+":"+marker,s.document(),s.title(),s.aliases(),s.body(),s.version())).toList());}
  /** 记录真实文档嵌入次数，重启复用不能通过重复付费/计算全部向量来冒充索引复用。 */
  private static final class Counting implements EmbeddingClient {
    private final EmbeddingClient delegate;int documents;
    /** 包装本地服务，查询仍使用真实模型。 */
    Counting(EmbeddingClient delegate){this.delegate=delegate;}
    /** 返回真实模型身份。 */
    public String fingerprint()throws Exception{return delegate.fingerprint();}
    /** 使用真实tokenizer分片，测试不能把字符数冒充token数。 */
    public List<Piece> split(List<KnowledgeDocumentService.Section> sections)throws Exception{return delegate.split(sections);}
    /** 仅计数文档嵌入，不把问题嵌入算成重复建库。 */
    public List<List<Double>> embed(List<String> texts,boolean query,int timeout)throws Exception{if(!query)documents+=texts.size();return delegate.embed(texts,query,timeout);}
  }
  /** 完整建库、重启复用、版本更新/删除、混合检索和引用生成；只删除本测试登记的随机集合。 */
  @Test void verifiesRealEmbeddingIndexLifecycleAndGroundedAnswer()throws Exception{
    String chroma=endpoint("SECURITIES_TEST_CHROMA_URL","http://127.0.0.1:18080");var vectors=new ChromaKnowledgeRepository(chroma);var embedding=new Counting(new HttpEmbeddingClient(endpoint("SECURITIES_TEST_EMBEDDING_URL","http://127.0.0.1:18081")));var documents=documents(UUID.randomUUID().toString());var owned=new ArrayList<KnowledgeIndexService.Index>();
    try(var index=new KnowledgeIndexService(true,documents,embedding,vectors)){
      // 模拟上次建库中断：只持久化一片，新进程在完整重建前不可发布该代次。
      var expected=documents.chunks(embedding.split(documents.sections()));String generation=KnowledgeIndexService.generation(documents.contentHash(),embedding.fingerprint(),expected);var partial=vectors.collection("securities-knowledge-"+generation.substring(0,32),generation);owned.add(new KnowledgeIndexService.Index(partial,generation,expected));
      vectors.upsert(partial,expected.subList(0,1),embedding.embed(List.of(expected.get(0).text()),false,15000),generation);assertFalse(vectors.complete(partial,expected,generation));assertNull(index.current());
      index.refresh();assertEquals("READY",index.status().get("state"),index.status().toString());int created=embedding.documents;assertTrue(created>=10);assertTrue(vectors.complete(index.current().collection(),index.current().chunks(),index.current().generation()));
      try(var restart=new KnowledgeIndexService(true,documents,embedding,vectors)){restart.refresh();assertEquals("READY",restart.status().get("state"));assertEquals(created,embedding.documents);assertEquals(index.current().collection().id(),restart.current().collection().id());}
      var retrieval=new KnowledgeRetrievalService(documents,index,embedding,vectors,3,0.46);var matches=retrieval.retrieve("部分成交后撤单，已成交的股票会回去吗");assertFalse(matches.isEmpty());assertTrue(matches.stream().anyMatch(c->c.section().title().contains("部分成交")));
      evaluateRetrieval(retrieval,embedding,vectors,index.current());
      // 用明确失败的嵌入适配器模拟知识依赖故障，不停止共享容器或访问供应商。
      EmbeddingClient unavailable=new EmbeddingClient(){/** 测试身份读取拒绝。 */public String fingerprint(){throw new IllegalStateException("TEST_UNAVAILABLE");}/** 测试切片拒绝。 */public List<Piece> split(List<KnowledgeDocumentService.Section> s){throw new IllegalStateException("TEST_UNAVAILABLE");}/** 模拟请求嵌入失败。 */public List<List<Double>> embed(List<String> t,boolean q,int timeout){throw new IllegalStateException("TEST_UNAVAILABLE");}};
      var degraded=new AssistantKnowledgeService(documents,index,new KnowledgeRetrievalService(documents,index,unavailable,vectors,3,0.46),null,3).read("如何充值");
      assertEquals("LEXICAL_FALLBACK",degraded.get("ragMode"));assertEquals("RAG_RETRIEVAL_UNAVAILABLE",degraded.get("ragReason"));assertTrue(Boolean.TRUE.equals(degraded.get("found")));
      var calls=new java.util.concurrent.atomic.AtomicInteger();KnowledgeAnswerClient fake=new KnowledgeAnswerClient(){/** 测试生成已启用，但不会访问供应商。 */public boolean configured(){return true;}/** 只回显本次证据的第一句话及对应引用。 */public Answer answer(String q,List<Evidence> evidence){calls.incrementAndGet();String sentence=evidence.get(0).text().split("\n",2)[1].split("。",2)[0];return new Answer(true,sentence+"[K1]。",List.of("K1"));}};
      var knowledge=new AssistantKnowledgeService(documents,index,retrieval,fake,3);var answer=knowledge.read("部分成交后撤单会怎样");assertEquals("RAG_GENERATED",answer.get("ragMode"));assertTrue(answer.get("answer").toString().contains("[K1]"));assertEquals(1,calls.get());assertEquals(false,knowledge.read("如何办理IPO发行审核").get("found"));assertEquals(1,calls.get());
      KnowledgeAnswerClient failed=new KnowledgeAnswerClient(){/** 模拟上游已配置。 */public boolean configured(){return true;}/** 模拟超时，不发网络请求。 */public Answer answer(String q,List<Evidence> e)throws Exception{throw new java.util.concurrent.TimeoutException("test");}};var fallback=new AssistantKnowledgeService(documents,index,retrieval,failed,3).read("如何充值");assertEquals("RAG_EXTRACTIVE_FALLBACK",fallback.get("ragMode"));assertFalse(((List<?>)fallback.get("sources")).isEmpty());
      var changed=new ArrayList<>(documents.sections());changed.remove(changed.size()-1);var s=changed.get(0);changed.set(0,new KnowledgeDocumentService.Section(s.id(),s.document(),s.title(),s.aliases(),s.body()+" 测试版本更新说明。",s.version()+"-updated"));var updated=new KnowledgeDocumentService(RagLogicTest.policy(),changed);
      try(var next=new KnowledgeIndexService(true,updated,embedding,vectors)){next.refresh();assertEquals("READY",next.status().get("state"),next.status().toString());owned.add(next.current());assertNotEquals(index.current().generation(),next.current().generation());assertEquals(changed.size(),next.current().chunks().size());}
    }finally{for(var view:owned){assertTrue(view.chunks().stream().allMatch(c->c.section().id().contains(":")&&documents.sections().stream().anyMatch(s->s.id().equals(c.section().id()))));String url=chroma+"/api/v2/tenants/default_tenant/databases/default_database/collections/"+view.collection().name();var row=RagHttp.exchange(url,"GET",null,null,1500,8192);assertEquals(view.generation(),row.path("metadata").path("generation").asText());RagHttp.exchange(url,"DELETE",null,null,1500,8192);}}
  }
  /** 小型公开问题集报告Recall@3和无关召回，阈值不是正确概率，也不代表生成答案质量。 */
  private void evaluateRetrieval(KnowledgeRetrievalService retrieval,EmbeddingClient embedding,ChromaKnowledgeRepository vectors,KnowledgeIndexService.Index view)throws Exception{
    String[][] positives={
      {"如何充值","入金入口"},{"我该怎么充值","入金入口"},{"模拟入金的上限是多少","入金入口"},{"新账号在哪里加模拟美元","入金入口"},
      {"pending是什么意思","申请状态"},{"充值一直待确认怎么办","申请状态"},{"重复入金会怎样","申请状态"},{"succeeded代表什么","申请状态"},
      {"限价单是什么意思","限价委托"},{"能不能买半股","限价委托"},{"股票价格最小单位","限价委托"},{"交易规则有哪些","限价委托"},
      {"成交价格按照谁的报价","撮合优先级"},{"maker是什么意思","撮合优先级"},{"同样的报价谁先成交","撮合优先级"},{"买入报价高于成交价的钱去了哪里","撮合优先级"},
      {"限价单为什么冻结资金","买卖委托"},{"卖单会占用股票吗","买卖委托"},{"部分成交后撤单会怎样","部分成交"},{"撤掉剩下的订单会退回已经成交的股票吗","部分成交"},
      {"没有成交为什么没有K线","K线与成交"},{"行情摘要的涨跌幅是什么","K线与成交"},{"新用户怎么注册","注册与登录"},{"websocket断开怎么办","WebSocket"}};
    int correct=0,caseId=0;for(var row:positives){var hits=retrieval.retrieve(row[0]);if(hits.stream().anyMatch(c->c.section().title().contains(row[1])))correct++;else{var raw=vectors.query(view.collection(),embedding.embed(List.of(row[0]),true,1500).get(0),8,1000);double gold=raw.stream().filter(m->view.chunks().stream().anyMatch(c->c.id().equals(m.id())&&c.section().title().contains(row[1]))).mapToDouble(ChromaKnowledgeRepository.Match::distance).min().orElse(9);System.out.println("RAG missing case="+caseId+" expected-distance="+gold);}caseId++;}
    int rejected=0;for(String q:List.of("如何办理IPO发行审核","苹果公司今年财报营收多少","今天北京会下雨吗","用Python实现快速排序算法","英伟达下一季度利润预测","请介绍火星地形"))if(retrieval.retrieve(q).isEmpty())rejected++;
    System.out.println("RAG retrieval sample: relevant@3="+correct+"/24, unrelated-empty="+rejected+"/6; no paid generation");
    assertTrue(correct>=21,"公开问题证据召回不足："+correct+"/24");assertTrue(rejected>=4,"无关问题召回过多："+rejected+"/6");
  }
}
