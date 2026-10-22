/* 引擎 HTTP 代理：统一观测所有显式就绪检查，保留不可达和恢复中的 503 语义。 */
package com.itranswarp.exchange.service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.itranswarp.exchange.ApiError;
import com.itranswarp.exchange.ApiException;
import com.itranswarp.exchange.util.JsonUtil;
import com.itranswarp.exchange.support.LoggerSupport;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Proxy to access trading engine.
 */
@Component
public class TradingEngineApiProxyService extends LoggerSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private com.itranswarp.exchange.monitoring.PipelineMonitoring monitoring = new com.itranswarp.exchange.monitoring.PipelineMonitoring();

    @Value("#{exchangeConfiguration.apiEndpoints.tradingEngineApi}")
    private String tradingEngineInternalApiEndpoint;

    private OkHttpClient okhttpClient = new OkHttpClient.Builder()
            // set connect timeout:
            .connectTimeout(1, TimeUnit.SECONDS)
            // set read timeout:
            .readTimeout(1, TimeUnit.SECONDS)
            // set connection pool:
            .connectionPool(new ConnectionPool(20, 60, TimeUnit.SECONDS))
            // do not retry:
            .retryOnConnectionFailure(false).build();

    /** 统一观测控制器及发送入口的就绪检查，不改变超时、重试或 503 语义。 */
    public void requireReady() {
        long started = System.nanoTime();
        try {
            Map<?, ?> status = JsonUtil.readJson(get("/internal/status"), Map.class);
            if (!Boolean.TRUE.equals(status.get("ready"))) {
                throw new ApiException(ApiError.ENGINE_UNAVAILABLE, null, "交易引擎尚未就绪");
            }
            monitoring.count("engine_gate", "success");
        } catch (IOException | RuntimeException e) {
            monitoring.count("engine_gate", "failure");
            throw new ApiException(ApiError.ENGINE_UNAVAILABLE, null, "交易引擎未就绪或探针失败，请稍后重试");
        } finally {
            monitoring.duration("engine_gate", started);
        }
    }

    /** 转发查询；引擎 503 或连接失败均明确不可用，其余非成功状态保持原业务错误处理。 */
    public String get(String url) throws IOException {
        Request request = new Request.Builder().url(tradingEngineInternalApiEndpoint + url).header("Accept", "*/*")
                .build();
        try (Response response = okhttpClient.newCall(request).execute()) {
            if (response.code() == 503) {
                throw new ApiException(ApiError.ENGINE_UNAVAILABLE, null, "交易引擎尚未就绪，请稍后重试");
            }
            if (response.code() != 200) {
                logger.error("Internal api failed with code {}: {}", Integer.valueOf(response.code()), url);
                throw new ApiException(ApiError.OPERATION_TIMEOUT, null, "operation timeout.");
            }
            try (ResponseBody body = response.body()) {
                String json = body.string();
                if (json == null || json.isEmpty()) {
                    logger.error("Internal api failed with code 200 but empty response: {}", json);
                    throw new ApiException(ApiError.INTERNAL_SERVER_ERROR, null, "response is empty.");
                }
                return json;
            }
        } catch (IOException e) {
            throw new ApiException(ApiError.ENGINE_UNAVAILABLE, null, "交易引擎不可达，请稍后重试");
        }
    }
}
