/* 本地聊天配置测试：覆盖两个客户端，保留外部 HTTPS 和密钥边界。 */
package com.itranswarp.exchange.service.assistant;

import com.itranswarp.exchange.service.assistant.rag.HttpKnowledgeAnswerClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalChatConfigurationTest {
  /** 两个客户端都允许本机免密，但禁止把 HTTP 放宽到远程。 */
  @Test void permitsOnlyLoopbackInLocalMode() {
    String url = "http://127.0.0.1:11434/v1/chat/completions";
    assertTrue(new HttpAiModelClient(true,url,"local-model","",false,true).configured());
    assertTrue(new HttpKnowledgeAnswerClient(true,url,"local-model","","auto",true).configured());
    assertThrows(IllegalArgumentException.class, () ->
        new HttpAiModelClient(true,"http://192.168.1.2/chat","local-model","",false,true));
    assertThrows(IllegalArgumentException.class, () ->
        new HttpKnowledgeAnswerClient(true,"https://example.test/chat","local-model","","auto",true));
    assertThrows(IllegalArgumentException.class, () ->
        new HttpAiModelClient(true,url,"remote","key",false));
    assertFalse(new HttpKnowledgeAnswerClient(true,"https://example.test/chat","remote","","auto").configured());
  }
}
