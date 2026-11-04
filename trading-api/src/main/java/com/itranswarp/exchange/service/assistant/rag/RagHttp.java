/* RAG 有界传输：固定配置地址、有限响应和请求时间，不跟随重定向，不泄露上游异常正文。 */
package com.itranswarp.exchange.service.assistant.rag;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public final class RagHttp {
  public static final ObjectMapper JSON=new ObjectMapper();
  private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
  /** 工具类不可实例化。 */
  private RagHttp(){}
  /** 内部 HTTP 仅允许宿主机回环地址；聊天端点另行要求 HTTPS。 */
  public static String local(String base){var uri=URI.create(base);if(!"http".equals(uri.getScheme())||!java.util.Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("RAG内部地址必须为本机HTTP地址");return base.replaceAll("/$","");}
  /** 以类型化状态码代替上游正文；每个调用者决定是否降级，不在此重试。 */
  public static JsonNode exchange(String url,String method,Object body,String key,int timeout,int limit)throws Exception{
    if(timeout<1)throw new TimeoutException("RAG_DEADLINE");var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeout)).header("Accept","application/json");
    if(key!=null&&!key.isBlank())request.header("Authorization","Bearer "+key);
    if(body==null)request.method(method,HttpRequest.BodyPublishers.noBody());else{byte[] raw=JSON.writeValueAsBytes(body);if(raw.length>262144)throw new IllegalArgumentException("RAG请求过大");request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofByteArray(raw));}
    var future=CLIENT.sendAsync(request.build(),info->new Body(limit));
    try{var response=future.get(timeout+100L,TimeUnit.MILLISECONDS);if(response.statusCode()==404)return com.fasterxml.jackson.databind.node.MissingNode.getInstance();if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("RAG_UPSTREAM_"+response.statusCode());return JSON.readTree(response.body());}finally{future.cancel(true);}
  }
  /** 真正边读边限制内存，不能先读无限响应再检查字节数。 */
  private static final class Body implements HttpResponse.BodySubscriber<String>{
    private final int limit;private final java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();private final CompletableFuture<String> result=new CompletableFuture<>();private Flow.Subscription subscription;
    /** 上限由固定客户端设置，不接受用户指定。 */
    Body(int limit){this.limit=limit;}
    /** HttpClient 通过完成阶段接收有界正文。 */
    public CompletionStage<String> getBody(){return result;}
    /** 保存取消句柄，每轮仅请求一批数据。 */
    public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
    /** 达到上限立即取消，不继续消费服务器响应。 */
    public void onNext(java.util.List<ByteBuffer> chunks){for(var chunk:chunks){if(data.size()+chunk.remaining()>limit){subscription.cancel();result.completeExceptionally(new IllegalStateException("RAG_RESPONSE_LIMIT"));return;}byte[] bytes=new byte[chunk.remaining()];chunk.get(bytes);data.writeBytes(bytes);}subscription.request(1);}
    /** 失败直接完成异常，不伪造空检索结果。 */
    public void onError(Throwable error){result.completeExceptionally(error);}
    /** 完整响应仅按UTF8解码，不记录正文。 */
    public void onComplete(){result.complete(data.toString(StandardCharsets.UTF_8));}
  }
}
