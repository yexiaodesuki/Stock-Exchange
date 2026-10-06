/* 交易 API 就绪门控测试：不连接 Kafka 或 Docker，验证写入拦截、异步清理和 HTTP 503。 */
package com.itranswarp.exchange.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itranswarp.exchange.ApiError;
import com.itranswarp.exchange.ApiException;
import com.itranswarp.exchange.bean.OrderRequestBean;
import com.itranswarp.exchange.ctx.UserContext;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.message.event.AbstractEvent;
import com.itranswarp.exchange.message.event.OrderCancelEvent;
import com.itranswarp.exchange.message.event.OrderRequestEvent;
import com.itranswarp.exchange.message.event.TransferEvent;
import com.itranswarp.exchange.messaging.MessageProducer;
import com.itranswarp.exchange.web.api.TradingApiController;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

class EngineReadinessTest {
    /** 真实 HTTP 探针只接受明确 ready=true，503、错误码及损坏响应都应失败关闭。 */
    @Test
    void realProbeFailsClosedOnInvalidResponses() {
        realProxy(200, "{\"ready\":true}", false).requireReady();
        for (String body : new String[] { "{\"ready\":false}", "{}", "null", "非法 JSON" }) {
            ApiException error = assertThrows(ApiException.class,
                    () -> realProxy(200, body, false).requireReady());
            assertEquals(ApiError.ENGINE_UNAVAILABLE, error.error.error());
        }
        for (int status : new int[] { 503, 500 }) {
            assertEquals(ApiError.ENGINE_UNAVAILABLE,
                    assertThrows(ApiException.class, () -> realProxy(status, "{}", false).requireReady()).error.error());
        }
    }

    /** 引擎不可达时查询和写入探针都返回明确不可用，而不是成功空资产。 */
    @Test
    void connectionFailureBecomesUnavailable() {
        TradingEngineApiProxyService proxy = realProxy(200, "{}", true);
        assertEquals(ApiError.ENGINE_UNAVAILABLE,
                assertThrows(ApiException.class, proxy::requireReady).error.error());
        assertEquals(ApiError.ENGINE_UNAVAILABLE,
                assertThrows(ApiException.class, () -> proxy.get("/internal/1001/assets")).error.error());
    }

    /** 装配真实代理，用 HTTP 拦截器控制响应或 IOException，不访问已有应用端口。 */
    private TradingEngineApiProxyService realProxy(int status, String body, boolean failConnection) {
        TradingEngineApiProxyService proxy = new TradingEngineApiProxyService();
        ReflectionTestUtils.setField(proxy, "tradingEngineInternalApiEndpoint", "http://localhost:8002");
        // 用固定响应或连接错误替代网络调用，保留真实代理判断。
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (failConnection) throw new IOException("模拟连接失败");
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("test").body(ResponseBody.create(body, MediaType.get("application/json")))
                    .build();
        }).build();
        ReflectionTestUtils.setField(proxy, "okhttpClient", http);
        return proxy;
    }

    /** 统一发送入口拦截下单、撤单、转账，拒绝时生产者不能被调用。 */
    @Test
    void unavailableEngineBlocksEveryCommandType() {
        StubProxy proxy = new StubProxy();
        proxy.ready = false;
        AtomicInteger sent = new AtomicInteger();
        SendEventService sender = sender(proxy, sent);
        for (AbstractEvent e : new AbstractEvent[] { new OrderRequestEvent(), new OrderCancelEvent(), new TransferEvent() }) {
            ApiException error = assertThrows(ApiException.class, () -> sender.sendMessage(e));
            assertEquals(ApiError.ENGINE_UNAVAILABLE, error.error.error());
        }
        assertEquals(0, sent.get());
    }

    /** 引擎就绪后仍使用原生产者发送一次，不改变消息协议。 */
    @Test
    void readyEngineAllowsSending() {
        AtomicInteger sent = new AtomicInteger();
        sender(new StubProxy(), sent).sendMessage(new TransferEvent());
        assertEquals(1, sent.get());
    }

    /** 下单初次探针不通过时，在建立异步等待项之前拒绝请求。 */
    @Test
    void createOrderRejectsBeforeAllocatingDeferredRequest() {
        StubProxy proxy = new StubProxy();
        proxy.ready = false;
        TradingApiController controller = controller(proxy, sender(proxy, new AtomicInteger()));
        try (UserContext ignored = new UserContext(1001L)) {
            assertThrows(ApiException.class, () -> controller.createOrder(order()));
        }
        assertTrue(pending(controller).isEmpty());
    }

    /** 探针之后发送之前变为不可用，下单及撤单失败必须移除已经建立的等待项。 */
    @Test
    void sendFailureRemovesDeferredRequest() {
        StubProxy precheck = new StubProxy();
        StubProxy failedAtSend = new StubProxy();
        failedAtSend.ready = false;
        AtomicInteger sent = new AtomicInteger();
        TradingApiController controller = controller(precheck, sender(failedAtSend, sent));
        try (UserContext ignored = new UserContext(1001L)) {
            assertThrows(ApiException.class, () -> controller.createOrder(order()));
            assertThrows(ApiException.class, () -> controller.cancelOrder(1L));
        }
        assertTrue(pending(controller).isEmpty());
        assertEquals(0, sent.get());
    }

    /** 恢复中经真实 API 异常处理返回 503，盘口不能读取 Redis 旧值伪装成成功。 */
    @Test
    void queryErrorsRemainHttp503() throws Exception {
        StubProxy proxy = new StubProxy();
        proxy.ready = false;
        TradingApiController controller = controller(proxy, sender(proxy, new AtomicInteger()));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try (UserContext ignored = new UserContext(1001L)) {
            for (String path : new String[] { "/api/assets", "/api/orders", "/api/orderBook" }) {
                var response = mvc.perform(get(path)).andReturn().getResponse();
                assertEquals(503, response.getStatus());
                assertTrue(response.getContentAsString().contains("ENGINE_UNAVAILABLE"));
            }
        }
    }

    /** 注入独立生产者计数器，证明拒绝请求没有发送 Kafka 命令。 */
    private SendEventService sender(StubProxy proxy, AtomicInteger sent) {
        SendEventService service = new SendEventService();
        ReflectionTestUtils.setField(service, "engineProxy", proxy);
        // 替身生产者只计数，不访问外部 Topic。
        MessageProducer<AbstractEvent> producer = event -> sent.incrementAndGet();
        ReflectionTestUtils.setField(service, "messageProducer", producer);
        return service;
    }

    /** 装配真实控制器和内存代理，不启动 Redis 订阅。 */
    private TradingApiController controller(StubProxy proxy, SendEventService sender) {
        TradingApiController controller = new TradingApiController();
        ReflectionTestUtils.setField(controller, "tradingEngineApiProxyService", proxy);
        ReflectionTestUtils.setField(controller, "sendEventService", sender);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
        return controller;
    }

    /** 读取异步等待表以检查发送失败后的清理。 */
    private Map<?, ?> pending(TradingApiController controller) {
        return (Map<?, ?>) ReflectionTestUtils.getField(controller, "deferredResultMap");
    }

    /** 构造通过校验的买单，避免把参数拒绝误认为就绪门控生效。 */
    private OrderRequestBean order() {
        OrderRequestBean order = new OrderRequestBean();
        order.direction = Direction.BUY;
        order.price = new BigDecimal("100");
        order.quantity = new BigDecimal("0.1");
        return order;
    }

    private static class StubProxy extends TradingEngineApiProxyService {
        boolean ready = true;

        /** 可控探针模拟恢复中，不依赖网络故障。 */
        @Override
        public void requireReady() {
            if (!ready) throw new ApiException(ApiError.ENGINE_UNAVAILABLE, "RECOVERING", "恢复中");
        }

        /** 模拟活动订单查询，失败仍走共享错误处理。 */
        @Override
        public String get(String url) {
            requireReady();
            return "{}";
        }
    }
}
