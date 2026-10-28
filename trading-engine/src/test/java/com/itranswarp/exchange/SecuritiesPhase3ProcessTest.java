/* 五个真实服务联调：API 身份与幂等、五证券行情、用户/证券订阅隔离、缓存回源和重建。 */
package com.itranswarp.exchange;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.apache.kafka.clients.admin.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.*;
import com.itranswarp.exchange.securities.*;
import com.itranswarp.exchange.securities.Currency;
import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.persistence.EventJournal;
import com.itranswarp.exchange.securities.output.*;
import io.lettuce.core.*;
import static com.itranswarp.exchange.SecuritiesCoreTest.*;
import static com.itranswarp.exchange.SecuritiesPersistenceTest.request;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SECURITIES_PHASE2_DB_URL",matches=".+")
class SecuritiesPhase3ProcessTest {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,Process> processes=new HashMap<>();
    private final Map<String,Integer> ports=new HashMap<>();
    private String namespace,key,redisUri;private Path logs;

    /** 真正运行五个独立证券包，测试后只清理本次随机库、消息前缀和 JVM。 */
    @Test void verifiesMarketsQueriesSubscriptionsAndCacheRecovery()throws Exception{
        var source=SecuritiesPersistenceTest.source();TestDatabase.reset(source);var jdbc=new JdbcTemplate(source);var catalog=SecurityCatalog.load(source);
        namespace="securities.test."+UUID.randomUUID().toString().replace("-","");key=UUID.randomUUID().toString()+UUID.randomUUID();redisUri=System.getenv("SECURITIES_TEST_REDIS_URI");
        logs=Files.createTempDirectory("securities-phase3-process-");System.out.println("第三阶段真实进程日志目录："+logs);
        for(String module:List.of("trading-engine","trading-sequencer","quotation","trading-api","push"))ports.put(module,port());
        String bootstrap=System.getenv("SECURITIES_TEST_BOOTSTRAP");var channels=new SecuritiesChannels(namespace);
        var topics=List.of("sequence","transfer","trade","tick").stream().map(channels::topic).toList();
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",bootstrap,"request.timeout.ms",5000,"default.api.timeout.ms",10000));
                var redis=RedisClient.create(redisUri);var redisConnection=redis.connect()){
            admin.createTopics(topics.stream().map(t->new NewTopic(t,1,(short)1)).toList()).all().get(15,TimeUnit.SECONDS);
            Collector first=new Collector(),second=new Collector();WebSocket a=null,b=null;
            try{
                var journal=new EventJournal(source,catalog);journal.append(request("seed-cash",2,new CreditCash(Currency.USD,n("10000"))));
                for(long security=1001;security<=1005;security++)journal.append(request("seed:"+security,3,new CreditPosition(security,n("100"))));
                start("trading-engine",redisUri);await(()->status("trading-engine","/internal/securities/status")==200,"核心就绪");
                start("trading-sequencer",redisUri);start("quotation",redisUri);start("trading-api",redisUri);start("push",redisUri);
                await(()->status("trading-api","/api/v1/securities")==200&&status("push","/actuator/health")==200,"API 与 Redis 订阅就绪");
                var tokens=new SecuritiesTokens(key);String buyer=tokens.issue(2,System.currentTimeMillis()+600000),seller=tokens.issue(3,System.currentTimeMillis()+600000),observer=tokens.issue(4,System.currentTimeMillis()+600000);
                assertEquals(401,get("/api/v1/account",null).statusCode());
                String ticketA=tree(post("/api/v1/ws-ticket",buyer,null,"{}")).path("ticket").asText();
                a=socket(ticketA,first);b=socket(tree(post("/api/v1/ws-ticket",observer,null,"{}")).path("ticket").asText(),second);
                a.sendText("{\"action\":\"subscribe\",\"securityIds\":[1001]}",true).join();b.sendText("{\"action\":\"subscribe\",\"securityIds\":[1002]}",true).join();
                await(()->first.has("subscription.ack")&&second.has("subscription.ack"),"证券订阅确认");
                assertThrows(Exception.class,()->socket(ticketA,new Collector()));
                String firstBuy=null;
                for(long security=1001;security<=1005;security++){
                    int price=(int)(security-1000)*10;
                    order(seller,"sell:"+security,security,"SELL",price,5);
                    String result=order(buyer,"buy:"+security,security,"BUY",price+1,2);if(security==1001)firstBuy=result;
                }
                await(()->jdbc.queryForObject("SELECT COUNT(*) FROM ticks",Integer.class)==5,"五证券行情投影");
                for(long security=1001;security<=1005;security++){
                    long selected=security;await(()->get("/api/v1/securities/"+selected+"/ticks",null).statusCode()==200,"行情查询可用");
                    var tick=tree(get("/api/v1/securities/"+security+"/ticks",null)).path("data").get(0);
                    assertEquals(Long.toString(security),tick.path("securityId").asText());amount("2",n(tick.path("quantity").asText()));
                    assertFalse(tick.has("buyerId")||tick.has("sellerId")||tick.has("userId"));
                    var bar=tree(get("/api/v1/securities/"+security+"/bars/min",null)).path("data").get(0);amount("2",n(bar.path("quantity").asText()));
                    assertEquals(1,tree(get("/api/v1/orders?securityId="+security,seller)).size());
                }
                amount("9700",n(tree(get("/api/v1/account?userId=3",buyer)).path("cash").path("available").asText()));
                assertEquals(404,get("/api/v1/requests/"+firstBuy,observer).statusCode());
                assertEquals(409,post("/api/v1/orders",buyer,"buy:1001","{\"securityId\":1001,\"direction\":\"BUY\",\"price\":11,\"quantity\":3}").statusCode());
                await(()->first.has("market.updated")&&first.has("account.changed")&&second.has("market.updated"),"公开与私人推送");
                assertTrue(first.messages.stream().filter(node->node.path("type").asText().equals("market.updated")||node.path("type").asText().equals("orderbook")).allMatch(node->node.path("securityId").asLong()==1001));
                assertTrue(second.messages.stream().filter(node->node.path("type").asText().equals("market.updated")||node.path("type").asText().equals("orderbook")).allMatch(node->node.path("securityId").asLong()==1002));
                assertFalse(second.has("account.changed"));
                await(()->first.messages.stream().anyMatch(node->node.path("type").asText().equals("account.changed")&&node.path("securityId").asLong()==1002),"未订阅 GREEN 仍收到本人 GREEN 提示");
                long sellId=tree(get("/api/v1/orders?securityId=1001",seller)).get(0).path("id").asLong();
                var cancellation=post("/api/v1/orders/"+sellId+"/cancel?securityId=1001",seller,"cancel-star","{}");assertEquals(202,cancellation.statusCode());String cancelId=tree(cancellation).path("requestId").asText();
                await(()->get("/api/v1/requests/"+cancelId,seller).statusCode()==200,"API 部分成交撤单");assertTrue(tree(get("/api/v1/requests/"+cancelId,seller)).path("accepted").asBoolean());
                var history=tree(get("/api/v1/history/orders?securityId=1001",seller));assertEquals("PARTIAL_CANCELLED",history.get(0).path("status").asText());
                assertEquals(404,get("/api/v1/orders/"+sellId+"/matches?securityId=1001",buyer).statusCode());
                var ownMatches=tree(get("/api/v1/orders/"+sellId+"/matches?securityId=1001",seller));assertEquals(1,ownMatches.size());assertFalse(ownMatches.get(0).has("counterUserId"));
                a.sendText("{\"action\":\"unsubscribe\",\"securityIds\":[1001]}",true).join();
                await(()->first.messages.stream().anyMatch(node->node.path("type").asText().equals("subscription.ack")&&node.path("securityIds").isEmpty()),"取消 STAR 行情订阅");
                // 重发 Tick 输出，重复消息不能使数据库成交量翻倍。
                jdbc.update("UPDATE event_outbox SET sentAt=NULL WHERE destination='TICK'");Thread.sleep(500);
                assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM ticks",Integer.class));
                stop("quotation");redisConnection.sync().del(namespace+":1001:quotation");
                var fallback=tree(get("/api/v1/securities/1001/ticks",null));assertEquals("mysql",fallback.path("source").asText());assertEquals(1,fallback.path("data").size());
                start("quotation",redisUri);await(()->redisConnection.sync().exists(namespace+":1001:quotation")==1,"行情重启重建缓存");
                // 原 API 故意连接不可达 Redis，私有资金查询与持久行情仍应可用。
                stop("trading-api");start("trading-api","redis://127.0.0.1:1/0");await(()->status("trading-api","/api/v1/securities")==200,"降级 API 启动");
                var offline=tree(get("/api/v1/securities/1001/ticks",null));assertEquals("mysql",offline.path("source").asText());amount("9700",n(tree(get("/api/v1/account",buyer)).path("cash").path("available").asText()));
                assertEquals(503,post("/api/v1/ws-ticket",buyer,null,"{}").statusCode());
            }finally{
                if(a!=null)a.abort();if(b!=null)b.abort();for(String module:new ArrayList<>(processes.keySet()))stop(module);
                try{var groups=admin.listConsumerGroups().all().get(15,TimeUnit.SECONDS).stream().map(ConsumerGroupListing::groupId).filter(g->g.startsWith(namespace+".")).toList();
                    if(!groups.isEmpty()){await(()->admin.describeConsumerGroups(groups).all().get(5,TimeUnit.SECONDS).values().stream().allMatch(g->g.members().isEmpty()),"消费组退出");admin.deleteConsumerGroups(groups).all().get(15,TimeUnit.SECONDS);}
                }finally{admin.deleteTopics(topics).all().get(15,TimeUnit.SECONDS);}
                // 只清理经过随机命名且由本测试拥有的 Redis 键。
                var cursor=redisConnection.sync().scan(ScanArgs.Builder.matches(namespace+":*").limit(100));while(true){if(!cursor.getKeys().isEmpty())redisConnection.sync().del(cursor.getKeys().toArray(String[]::new));if(cursor.isFinished())break;cursor=redisConnection.sync().scan(cursor,ScanArgs.Builder.matches(namespace+":*").limit(100));}
            }
        }
    }
    /** 下单并查询持久最终结果，不把 202 当成撮合成功。 */
    private String order(String token,String id,long security,String direction,int price,int quantity)throws Exception{
        var response=post("/api/v1/orders",token,id,"{\"securityId\":"+security+",\"direction\":\""+direction+"\",\"price\":"+price+",\"quantity\":"+quantity+"}");assertEquals(202,response.statusCode(),response.body());String request=tree(response).path("requestId").asText();
        await(()->get("/api/v1/requests/"+request,token).statusCode()==200,"持久执行结果");assertTrue(tree(get("/api/v1/requests/"+request,token)).path("accepted").asBoolean());return request;
    }
    /** 建立实际 WebSocket 握手，Origin 与一次性票据均参与验证。 */
    private WebSocket socket(String ticket,Collector collector)throws Exception{return http.newWebSocketBuilder().header("Origin","http://localhost:8100").connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create("ws://127.0.0.1:"+ports.get("push")+"/ws?ticket="+ticket),collector).get(8,TimeUnit.SECONDS);}
    /** 随机本机端口不覆盖用户应用。 */
    private int port()throws Exception{try(var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}}
    /** 五服务共用临时库和随机消息前缀，凭据仅通过环境变量传递。 */
    private void start(String module,String redis)throws Exception{
        Path root=Path.of(System.getenv("SECURITIES_TEST_ROOT"));var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xms24m","-Xmx112m","-XX:MaxMetaspaceSize=160m","-XX:ActiveProcessorCount=2","-Dfile.encoding=UTF-8","-jar",root.resolve(module+"/target/"+module+".jar").toString(),"--server.port="+ports.get(module),"--management.server.port=0","--logging.level.root=WARN");
        var environment=builder.environment();environment.put("SECURITIES_JDBC_URL",System.getenv("SECURITIES_PHASE2_DB_URL"));environment.put("SECURITIES_NAMESPACE",namespace);environment.put("SECURITIES_AUTH_KEY",key);environment.put("SEC_REDIS_URI",redis);environment.put("SEC_KAFKA_BOOTSTRAP_SERVERS",System.getenv("SECURITIES_TEST_BOOTSTRAP"));environment.put("SECURITIES_ENGINE_URL","http://127.0.0.1:"+ports.get("trading-engine"));environment.put("SECURITIES_OUTPUT_SEND_ENABLED","true");
        processes.put(module,builder.redirectErrorStream(true).redirectOutput(logs.resolve(module+"-"+System.nanoTime()+".log").toFile()).start());
    }
    /** 只停止本测试创建的 JVM，超时才强制终止。 */
    private void stop(String module)throws Exception{var process=processes.remove(module);if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(12,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(12,TimeUnit.SECONDS);}}}
    /** 带短超时的 GET，非 200 状态交给断言判断。 */
    private HttpResponse<String> get(String path,String token)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+ports.get("trading-api")+path)).timeout(Duration.ofSeconds(8));if(token!=null)builder.header("Authorization","Bearer "+token);return http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString());}
    /** 写请求仅发送订单和票据，不存在公开配给接口。 */
    private HttpResponse<String> post(String path,String token,String id,String body)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+ports.get("trading-api")+path)).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json");if(token!=null)builder.header("Authorization","Bearer "+token);if(id!=null)builder.header("Idempotency-Key",id);return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    /** 状态探测不假造服务成功。 */
    private int status(String module,String path){try{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+ports.get(module)+path)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();}catch(Exception unavailable){return 503;}}
    /** 解析响应留住原文用于断言排错，不打印令牌或密码。 */
    private JsonNode tree(HttpResponse<String> response)throws Exception{return json.readTree(response.body());}
    /** 等待可观察条件，不使用固定长睡眠掩盖启动或投影失败。 */
    private void await(Check condition,String description)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(80);while(System.nanoTime()<deadline){try{if(condition.test())return;}catch(Exception transientFailure){/* 允许启动瞬时不可达。 */}Thread.sleep(100);}fail("等待超时："+description+"，日志："+logs);}
    private interface Check{/** 条件可包含网络/数据库短时检查。 */boolean test()throws Exception;}
    private final class Collector implements WebSocket.Listener{
        final Queue<JsonNode> messages=new ConcurrentLinkedQueue<>();final StringBuilder buffer=new StringBuilder();
        /** 开始拉取服务器帧。 */
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        /** 完整消息才解析，不能把分片当成多条行情。 */
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){buffer.append(data);if(last){try{messages.add(json.readTree(buffer.toString()));}catch(Exception ignored){/* 后续断言会发现缺失预期类型。 */}buffer.setLength(0);}socket.request(1);return CompletableFuture.completedFuture(null);}
        /** 判断实际收到的消息而非仅 WebSocket 已连接。 */
        boolean has(String type){return messages.stream().anyMatch(node->node.path("type").asText().equals(type));}
    }
}
