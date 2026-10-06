/* 交易与行情 HTTP 入口：引擎状态查询和交易依赖就绪门控，历史行情仍由原服务负责。 */
package com.itranswarp.exchange.web.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itranswarp.exchange.ApiError;
import com.itranswarp.exchange.ApiErrorResponse;
import com.itranswarp.exchange.ApiException;
import com.itranswarp.exchange.bean.OrderBookBean;
import com.itranswarp.exchange.bean.OrderRequestBean;
import com.itranswarp.exchange.bean.SimpleMatchDetailRecord;
import com.itranswarp.exchange.ctx.UserContext;
import com.itranswarp.exchange.message.ApiResultMessage;
import com.itranswarp.exchange.message.event.OrderCancelEvent;
import com.itranswarp.exchange.message.event.OrderRequestEvent;
import com.itranswarp.exchange.model.trade.OrderEntity;
import com.itranswarp.exchange.redis.RedisCache;
import com.itranswarp.exchange.redis.RedisService;
import com.itranswarp.exchange.service.TradingEngineApiProxyService;
import com.itranswarp.exchange.support.AbstractApiController;
import com.itranswarp.exchange.service.HistoryService;
import com.itranswarp.exchange.service.SendEventService;
import com.itranswarp.exchange.util.IdUtil;
import com.itranswarp.exchange.util.JsonUtil;

@RestController
@RequestMapping("/api")
public class TradingApiController extends AbstractApiController {

    @Autowired
    private HistoryService historyService;

    @Autowired
    private SendEventService sendEventService;

    @Autowired
    private RedisService redisService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TradingEngineApiProxyService tradingEngineApiProxyService;

    private Long asyncTimeout = Long.valueOf(500);

    private String timeoutJson = null;

    Map<String, DeferredResult<ResponseEntity<String>>> deferredResultMap = new ConcurrentHashMap<>();

    private String getTimeoutJson() throws IOException {
        if (timeoutJson == null) {
            timeoutJson = this.objectMapper
                    .writeValueAsString(new ApiErrorResponse(ApiError.OPERATION_TIMEOUT, null, ""));
        }
        return timeoutJson;
    }

    @PostConstruct
    public void init() {
        this.redisService.subscribe(RedisCache.Topic.TRADING_API_RESULT, this::onApiResultMessage);
    }

    @GetMapping("/timestamp")
    public Map<String, Long> timestamp() {
        return Map.of("timestamp", Long.valueOf(System.currentTimeMillis()));
    }

    @ResponseBody
    @GetMapping(value = "/assets", produces = "application/json")
    public String getAssets() throws IOException {
        return tradingEngineApiProxyService.get("/internal/" + UserContext.getRequiredUserId() + "/assets");
    }

    @ResponseBody
    @GetMapping(value = "/orders/{orderId}", produces = "application/json")
    public String getOpenOrder(@PathVariable("orderId") Long orderId) throws IOException {
        final Long userId = UserContext.getRequiredUserId();
        return tradingEngineApiProxyService.get("/internal/" + userId + "/orders/" + orderId);
    }

    @ResponseBody
    @GetMapping(value = "/orders", produces = "application/json")
    public String getOpenOrders() throws IOException {
        return tradingEngineApiProxyService.get("/internal/" + UserContext.getRequiredUserId() + "/orders");
    }

    /** 先确认引擎恢复完成，避免将恢复前残留的 Redis 盘口当成当前可交易状态。 */
    @ResponseBody
    @GetMapping(value = "/orderBook", produces = "application/json")
    public String getOrderBook() {
        tradingEngineApiProxyService.requireReady();
        String data = redisService.get(RedisCache.Key.ORDER_BOOK);
        return data == null ? OrderBookBean.EMPTY : data;
    }

    @ResponseBody
    @GetMapping(value = "/ticks", produces = "application/json")
    public String getRecentTicks() {
        List<String> data = redisService.lrange(RedisCache.Key.RECENT_TICKS, 0, -1);
        if (data == null || data.isEmpty()) {
            return "[]";
        }
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (String t : data) {
            sj.add(t);
        }
        return sj.toString();
    }

    @ResponseBody
    @GetMapping(value = "/bars/day", produces = "application/json")
    public String getDayBars() {
        long end = System.currentTimeMillis();
        long start = end - 366 * 86400_000;
        return getBars(RedisCache.Key.HOUR_BARS, start, end);
    }

    @ResponseBody
    @GetMapping(value = "/bars/hour", produces = "application/json")
    public String getHourBars() {
        long end = System.currentTimeMillis();
        long start = end - 720 * 3600_000;
        return getBars(RedisCache.Key.HOUR_BARS, start, end);
    }

    @ResponseBody
    @GetMapping(value = "/bars/min", produces = "application/json")
    public String getMinBars() {
        long end = System.currentTimeMillis();
        long start = end - 1440 * 60_000;
        return getBars(RedisCache.Key.MIN_BARS, start, end);
    }

    @ResponseBody
    @GetMapping(value = "/bars/sec", produces = "application/json")
    public String getSecBars() {
        long end = System.currentTimeMillis();
        long start = end - 3600 * 1_000;
        return getBars(RedisCache.Key.SEC_BARS, start, end);
    }

    private String getBars(String key, long start, long end) {
        List<String> data = redisService.zrangebyscore(key, start, end);
        if (data == null || data.isEmpty()) {
            return "[]";
        }
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (String t : data) {
            sj.add(t);
        }
        return sj.toString();
    }

    @GetMapping("/history/orders")
    public List<OrderEntity> getHistoryOrders(
            @RequestParam(value = "maxResults", defaultValue = "100") int maxResults) {
        if (maxResults < 1 || maxResults > 1000) {
            throw new ApiException(ApiError.PARAMETER_INVALID, "maxResults", "Invalid parameter.");
        }
        return historyService.getHistoryOrders(UserContext.getRequiredUserId(), maxResults);
    }

    @GetMapping("/history/orders/{orderId}/matches")
    public List<SimpleMatchDetailRecord> getOrderMatchDetails(@PathVariable("orderId") Long orderId) throws Exception {
        final Long userId = UserContext.getRequiredUserId();
        // 查找活动Order:
        String strOpenOrder = tradingEngineApiProxyService.get("/internal/" + userId + "/orders/" + orderId);
        if (strOpenOrder.equals("null")) {
            // 查找历史Order:
            OrderEntity orderEntity = this.historyService.getHistoryOrder(userId, orderId);
            if (orderEntity == null) {
                // Order未找到:
                throw new ApiException(ApiError.ORDER_NOT_FOUND, orderId.toString(), "Order not found.");
            }
        }
        return this.historyService.getHistoryMatchDetails(orderId);
    }

    /** 撤单前检查归属与就绪；若发送失败，移除异步等待项，防止留下无响应请求。 */
    @PostMapping(value = "/orders/{orderId}/cancel", produces = "application/json")
    @ResponseBody
    public DeferredResult<ResponseEntity<String>> cancelOrder(@PathVariable("orderId") Long orderId) throws Exception {
        final Long userId = UserContext.getRequiredUserId();
        String orderStr = tradingEngineApiProxyService.get("/internal/" + userId + "/orders/" + orderId);
        if (orderStr.equals("null")) {
            throw new ApiException(ApiError.ORDER_NOT_FOUND, orderId.toString(), "Active order not found.");
        }
        final String refId = IdUtil.generateUniqueId();
        var message = new OrderCancelEvent();
        message.refId = refId;
        message.refOrderId = orderId;
        message.userId = userId;
        message.createdAt = System.currentTimeMillis();
        ResponseEntity<String> timeout = new ResponseEntity<>(getTimeoutJson(), HttpStatus.BAD_REQUEST);
        DeferredResult<ResponseEntity<String>> deferred = new DeferredResult<>(this.asyncTimeout, timeout);
        deferred.onTimeout(() -> {
            logger.warn("deferred order {} cancel request refId={} timeout.", orderId, refId);
            this.deferredResultMap.remove(refId);
        });
        // track deferred:
        this.deferredResultMap.put(refId, deferred);
        logger.info("cancel order message created: {}", message);
        try {
            this.sendEventService.sendMessage(message);
        } catch (RuntimeException e) {
            this.deferredResultMap.remove(refId);
            throw e;
        }
        return deferred;
    }

    /** 下单前检查引擎就绪；发送失败清理异步等待项，不让恢复中请求进入定序系统。 */
    @PostMapping(value = "/orders", produces = "application/json")
    @ResponseBody
    public DeferredResult<ResponseEntity<String>> createOrder(@RequestBody OrderRequestBean orderRequest)
            throws IOException {
        final Long userId = UserContext.getRequiredUserId();
        orderRequest.validate();
        tradingEngineApiProxyService.requireReady();
        final String refId = IdUtil.generateUniqueId();
        var event = new OrderRequestEvent();
        event.refId = refId;
        event.userId = userId;
        event.direction = orderRequest.direction;
        event.price = orderRequest.price;
        event.quantity = orderRequest.quantity;
        event.createdAt = System.currentTimeMillis();

        ResponseEntity<String> timeout = new ResponseEntity<>(getTimeoutJson(), HttpStatus.BAD_REQUEST);
        DeferredResult<ResponseEntity<String>> deferred = new DeferredResult<>(this.asyncTimeout, timeout);
        deferred.onTimeout(() -> {
            logger.warn("deferred order request refId = {} timeout.", event.refId);
            this.deferredResultMap.remove(event.refId);
        });
        // track deferred:
        this.deferredResultMap.put(event.refId, deferred);
        try {
            this.sendEventService.sendMessage(event);
        } catch (RuntimeException e) {
            this.deferredResultMap.remove(event.refId);
            throw e;
        }
        return deferred;
    }

    // message callback ///////////////////////////////////////////////////////

    public void onApiResultMessage(String msg) {
        logger.info("on subscribed message: {}", msg);
        try {
            ApiResultMessage message = objectMapper.readValue(msg, ApiResultMessage.class);
            if (message.refId != null) {
                DeferredResult<ResponseEntity<String>> deferred = this.deferredResultMap.remove(message.refId);
                if (deferred != null) {
                    if (message.error != null) {
                        String error = objectMapper.writeValueAsString(message.error);
                        ResponseEntity<String> resp = new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
                        deferred.setResult(resp);
                    } else {
                        ResponseEntity<String> resp = new ResponseEntity<>(JsonUtil.writeJson(message.result),
                                HttpStatus.OK);
                        deferred.setResult(resp);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Invalid ApiResultMessage: " + msg, e);
        }
    }
}
