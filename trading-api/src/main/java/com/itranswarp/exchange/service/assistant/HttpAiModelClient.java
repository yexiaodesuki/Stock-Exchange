/* 有界模型连接：兼容 chat/completions，仅分类本人查询、行情和知识问题，不发送业务数据。 */
package com.itranswarp.exchange.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public final class HttpAiModelClient implements AiModelClient {
  private final boolean enabled;
  private final String endpoint,model,key;
  private final boolean disableThinking;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper json=new ObjectMapper();
  /** 配置保存在后端，不允许用户传入上游 URL；空值允许项目正常启动。 */
  public HttpAiModelClient(boolean enabled,String endpoint,String model,String key) {
    this(enabled,endpoint,model,key,endpoint!=null&&"api.deepseek.com".equalsIgnoreCase(URI.create(endpoint.isBlank()?"https://unconfigured.invalid":endpoint).getHost()));
  }
  /** 供应商扩展参数可显式关闭；默认仅为已验证的 DeepSeek 官方域名关闭思考。 */
  public HttpAiModelClient(boolean enabled,String endpoint,String model,String key,boolean disableThinking) {
    this.enabled=enabled;this.endpoint=endpoint;this.model=model;this.key=key;
    this.disableThinking=disableThinking;
    if(enabled && !endpoint.isBlank() && !"https".equalsIgnoreCase(URI.create(endpoint).getScheme()))
      throw new IllegalArgumentException("模型接口必须使用 HTTPS");
  }
  /** 双重要求开关和完整凭据，未配置不产生收费调用。 */
  @Override public boolean configured(){return enabled&&!endpoint.isBlank()&&!model.isBlank()&&!key.isBlank();}
  /** 有界非思考分类，区分供应商拒绝、空答案、截断和协议错误，不回传供应商原文。 */
  @Override public String classify(String question) throws Exception {
    if(!configured())throw new IllegalStateException("模型未配置");
    var body=requestBody(question);
    var request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(2))
        .header("Authorization","Bearer "+key).header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    var future=http.sendAsync(request,info->new LimitedBody());
    try {
      var response=future.get(2300,TimeUnit.MILLISECONDS);
      return content(response.statusCode(),response.body());
    } catch(java.util.concurrent.TimeoutException timeout){throw new AssistantModelException("MODEL_TIMEOUT");
    } catch(java.util.concurrent.ExecutionException failure){
      Throwable cause=failure.getCause();
      if(cause instanceof java.net.http.HttpTimeoutException)throw new AssistantModelException("MODEL_TIMEOUT");
      throw new AssistantModelException("MODEL_NETWORK_OR_RESPONSE_ERROR");
    } catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new AssistantModelException("MODEL_INVALID_RESPONSE");
    } finally {future.cancel(true);}
  }
  /** 纯请求构造便于用假供应商测试扩展参数，不调用真实接口。 */
  Map<String,Object> requestBody(String question){
    var body=new java.util.LinkedHashMap<String,Object>(Map.of("model",model,"max_tokens",64,"messages",List.of(
        Map.of("role","system","content","仅分类用户问题，输出一个词：ACCOUNT, FREEZE, ORDER, DEPOSIT, MARKET, KNOWLEDGE, UNSUPPORTED。ACCOUNT账户余额持仓；FREEZE本人冻结；ORDER本人订单；DEPOSIT本人入金记录；MARKET本系统公开成交摘要；KNOWLEDGE交易规则和操作文档，例如如何充值或限价单规则。写操作、他人账户、价格预测一律UNSUPPORTED。不得输出数字、解释或工具调用。"),
        Map.of("role","user","content",question))));
    if(disableThinking)body.put("thinking",Map.of("type","disabled"));
    return body;
  }
  /** 状态码和响应形状分别检查，任何供应商原始错误正文不得进入页面或日志。 */
  String content(int status,String body) throws com.fasterxml.jackson.core.JsonProcessingException {
      if(status!=200)throw new AssistantModelException(switch(status){case 401,403->"MODEL_ACCESS_DENIED";case 404->"MODEL_ENDPOINT_OR_NAME_NOT_FOUND";case 429->"MODEL_RATE_LIMITED";default->"MODEL_HTTP_ERROR";});
      var choice=json.readTree(body).path("choices").path(0);
      if("length".equals(choice.path("finish_reason").asText()))throw new AssistantModelException("MODEL_TRUNCATED");
      String content=choice.path("message").path("content").asText().trim();
      if(content.isBlank())throw new AssistantModelException("MODEL_EMPTY_CONTENT");
      return content;
  }
  /** 限制累计字节，不能等无限响应完全读入后才检查大小。 */
  private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
    private final CompletableFuture<String> body=new CompletableFuture<>();
    private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
    private Flow.Subscription subscription;
    /** HttpClient 通过 Future 等待有界响应完成。 */
    public CompletionStage<String> getBody(){return body;}
    /** 一次领取一批数据，保留取消句柄。 */
    public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
    /** 超限立即取消连接，不保留更多响应。 */
    public void onNext(List<ByteBuffer> chunks){for(var chunk:chunks){if(bytes.size()+chunk.remaining()>16384){subscription.cancel();body.completeExceptionally(new IllegalStateException("模型响应过大"));return;}byte[] data=new byte[chunk.remaining()];chunk.get(data);bytes.writeBytes(data);}subscription.request(1);}
    /** 传输异常不会伪装成空分类结果。 */
    public void onError(Throwable error){body.completeExceptionally(error);}
    /** 只在完整读取后按 UTF-8 解码。 */
    public void onComplete(){body.complete(bytes.toString(StandardCharsets.UTF_8));}
  }
}
