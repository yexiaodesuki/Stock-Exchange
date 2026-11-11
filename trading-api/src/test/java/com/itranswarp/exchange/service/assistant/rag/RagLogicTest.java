/* 无收费RAG回归：切片覆盖、模型协议、引用与数值约束和原文降级，不访问正式交易数据。 */
package com.itranswarp.exchange.service.assistant.rag;
import static org.junit.jupiter.api.Assertions.*;
import com.itranswarp.exchange.service.DepositPolicy;
import com.itranswarp.exchange.service.assistant.AssistantKnowledgeService;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RagLogicTest {
  /** 小额配置证明动态事实与索引原文分离，测试无需读取真实.env。 */
  static DepositPolicy policy(){return new DepositPolicy(true,new BigDecimal("1.00"),new BigDecimal("100.00"),new BigDecimal("200.00"));}
  /** 相同原文不因当前额度改变而重复嵌入，正文改变则哈希随之改变。 */
  @Test void documentIdentityExcludesDynamicPolicy(){var first=new KnowledgeDocumentService(policy());var other=new KnowledgeDocumentService(new DepositPolicy(false,new BigDecimal("2.00"),new BigDecimal("50.00"),new BigDecimal("80.00")));assertEquals(first.contentHash(),other.contentHash());assertNotEquals(first.expand("{depositMinimum}"),other.expand("{depositMinimum}"));var changed=new ArrayList<>(first.sections());var s=changed.get(0);changed.set(0,new KnowledgeDocumentService.Section(s.id(),s.document(),s.title(),s.aliases(),s.body()+" 新规则。",s.version()));assertNotEquals(first.contentHash(),new KnowledgeDocumentService(policy(),changed).contentHash());}
  /** 重复原文必须按偏移完整覆盖；遗漏尾部、遗漏中间和未知章节一律不能索引。 */
  @Test void chunksCannotSilentlyLoseRules(){String body="甲乙丙丁".repeat(100);var s=new KnowledgeDocumentService.Section("test","规则","冻结",List.of("冻结"),body,"test-version");var documents=new KnowledgeDocumentService(policy(),List.of(s));String prefix="规则 · 冻结\n";var first=new EmbeddingClient.Piece("test",0,prefix+body.substring(0,200),220,0,200);var second=new EmbeddingClient.Piece("test",1,prefix+body.substring(160),260,160,400);assertEquals(2,documents.chunks(List.of(first,second)).size());assertThrows(IllegalStateException.class,()->documents.chunks(List.of(first)));var gap=new EmbeddingClient.Piece("test",1,prefix+body.substring(240),180,240,400);assertThrows(IllegalStateException.class,()->documents.chunks(List.of(first,gap)));assertThrows(IllegalStateException.class,()->documents.chunks(List.of(new EmbeddingClient.Piece("outside",0,prefix+body,200,0,400))));}
  /** 文档不足或RAG关闭时只返回原文，不触发网络或生成，未知问题保持拒答。 */
  @Test void disabledRagHasExplicitExtractiveMode(){var knowledge=new AssistantKnowledgeService(policy(),2);var result=knowledge.read("如何充值");assertEquals("LEXICAL_FALLBACK",result.get("ragMode"));assertEquals("RAG_DISABLED",result.get("ragReason"));assertEquals("",result.get("answer"));assertTrue(result.get("sources").toString().contains("100.00"));assertEquals(false,knowledge.read("如何办理IPO").get("found"));}
  /** 模型不能通过有效JSON伪造编号、漏写引用或添加证据之外的金额；拒答必须为空。 */
  @Test void generatedAnswersRequireRealEvidenceAndNumbers()throws Exception{
    var evidence=List.of(new KnowledgeAnswerClient.Evidence("K1","入金","额度","单笔1.00至100.00 USD。"));
    assertTrue(HttpKnowledgeAnswerClient.validate("{\"answerable\":true,\"answer\":\"单笔上限100 USD[K1]。\",\"citations\":[\"K1\"]}",evidence).answerable());
    for(String raw:List.of("{\"answerable\":true,\"answer\":\"上限999 USD[K1]\",\"citations\":[\"K1\"]}","{\"answerable\":true,\"answer\":\"上限100[K2]\",\"citations\":[\"K2\"]}","{\"answerable\":true,\"answer\":\"上限100\",\"citations\":[\"K1\"]}","{\"answerable\":false,\"answer\":\"猜一个答案\",\"citations\":[]}"))assertThrows(Exception.class,()->HttpKnowledgeAnswerClient.validate(raw,evidence));
    assertFalse(HttpKnowledgeAnswerClient.validate("{\"answerable\":false,\"answer\":\"\",\"citations\":[]}",evidence).answerable());
  }
  /** 请求构造不夹带认证密钥或任何私人对象；禁用时不生成，明文远端端点拒绝。 */
  @Test void generationRequestContainsOnlyPublicEvidence(){var client=new HttpKnowledgeAnswerClient(true,"https://api.deepseek.com/chat/completions","test","secret-not-in-prompt","auto");var request=client.request("规则是什么",List.of(new KnowledgeAnswerClient.Evidence("K1","公开文档","规则","公开说明")));String raw=request.toString();assertFalse(raw.contains("secret-not-in-prompt"));assertFalse(raw.contains("userId"));assertEquals(768,request.get("max_tokens"));assertEquals(Map.of("type","disabled"),request.get("thinking"));assertFalse(new HttpKnowledgeAnswerClient(false,"","","","auto").configured());assertThrows(IllegalArgumentException.class,()->new HttpKnowledgeAnswerClient(true,"http://remote.example","test","key","auto"));}
  /** 字符串即使可转换成归一化单位向量也不能当作JSON数值接受。 */
  @Test void embeddingRejectsNumericStrings()throws Exception{
    var strings=new ArrayList<String>(Collections.nCopies(512,"0"));strings.set(0,"1");
    byte[] bytes=RagHttp.JSON.writeValueAsBytes(Map.of("fingerprint",HttpEmbeddingClient.FINGERPRINT,"dimension",512,"embeddings",List.of(strings)));
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    // 本机假响应只测试协议，不消耗模型服务或聊天费用。
    server.createContext("/",exchange->{exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();
    try{assertThrows(Exception.class,()->new HttpEmbeddingClient("http://127.0.0.1:"+server.getAddress().getPort()).embed(List.of("规则"),true,500));}finally{server.stop(0);}
  }
  /** 假嵌入服务覆盖正确向量、错误维度、未归一化、错模型及部分批次，不下载模型。 */
  @Test void embeddingProtocolRejectsWrongModelOrVectors()throws Exception{
    var body=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{byte[] bytes=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}});server.start();
    try{var client=new HttpEmbeddingClient("http://127.0.0.1:"+server.getAddress().getPort());var vector=new ArrayList<Double>(Collections.nCopies(512,0.0));vector.set(0,1.0);body.set(RagHttp.JSON.writeValueAsString(Map.of("fingerprint",HttpEmbeddingClient.FINGERPRINT,"dimension",512,"embeddings",List.of(vector))));assertEquals(512,client.embed(List.of("公开文本"),true,500).get(0).size());for(var vectors:List.of(List.of(List.of(1.0)),List.of(Collections.nCopies(512,0.0)))){body.set(RagHttp.JSON.writeValueAsString(Map.of("fingerprint",HttpEmbeddingClient.FINGERPRINT,"dimension",512,"embeddings",vectors)));assertThrows(Exception.class,()->client.embed(List.of("公开文本"),true,500));}body.set("{\"fingerprint\":\"wrong-model\",\"dimension\":512,\"embeddings\":[]}");assertThrows(Exception.class,()->client.embed(List.of("公开文本"),true,500));body.set("x".repeat(100));assertThrows(Exception.class,()->RagHttp.exchange("http://127.0.0.1:"+server.getAddress().getPort(),"GET",null,null,500,16));}finally{server.stop(0);}
  }
}
