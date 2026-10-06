/* 引擎内部查询：恢复未完成时拒绝资产与订单查询，并提供可检查的就绪状态。 */
package com.itranswarp.exchange.web.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import com.itranswarp.exchange.TradingEngineService;

import com.itranswarp.exchange.assets.AssetService;
import com.itranswarp.exchange.assets.Asset;
import com.itranswarp.exchange.enums.AssetEnum;
import com.itranswarp.exchange.model.trade.OrderEntity;
import com.itranswarp.exchange.order.OrderService;
import com.itranswarp.exchange.support.AbstractApiController;

@RestController
@RequestMapping("/internal")
public class InternalTradingEngineApiController extends AbstractApiController {

    @Autowired
    TradingEngineService engine;

    /** 无需登录的内部就绪探针；恢复中或失败返回 503，不对外暴露业务资产。 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        TradingEngineService.RecoveryState state = engine.getRecoveryState();
        boolean ready = state == TradingEngineService.RecoveryState.READY;
        return ResponseEntity.status(ready ? 200 : 503).body(Map.of("state", state.name(), "ready", ready));
    }

    @Autowired
    OrderService orderService;

    @Autowired
    AssetService assetService;

    /** 仅就绪时读取用户资产，恢复中不能把不完整状态作为正常余额返回。 */
    @GetMapping("/{userId}/assets")
    public Map<AssetEnum, Asset> getAssets(@PathVariable("userId") Long userId) {
        engine.requireReady();
        return assetService.getAssets(userId);
    }

    /** 就绪后复制用户活动订单；重放期间不读取可变的中间订单。 */
    @GetMapping("/{userId}/orders")
    public List<OrderEntity> getOrders(@PathVariable("userId") Long userId) {
        engine.requireReady();
        ConcurrentMap<Long, OrderEntity> orders = orderService.getUserOrders(userId);
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        List<OrderEntity> list = new ArrayList<>(orders.size());
        for (OrderEntity order : orders.values()) {
            OrderEntity copy = null;
            while (copy == null) {
                copy = order.copy();
            }
            list.add(copy);
        }
        return list;
    }

    /** 就绪后检查归属并返回订单副本，恢复期间不把暂时缺失判断为订单不存在。 */
    @GetMapping("/{userId}/orders/{orderId}")
    public OrderEntity getOrders(@PathVariable("userId") Long userId, @PathVariable("orderId") Long orderId) {
        engine.requireReady();
        OrderEntity order = orderService.getOrder(orderId);
        if (order == null || order.userId.longValue() != userId.longValue()) {
            return null;
        }
        return order.copy();
    }
}
