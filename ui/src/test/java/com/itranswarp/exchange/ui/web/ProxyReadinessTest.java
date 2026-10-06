/* UI 代理状态测试：真实 RestClient 和代理过滤器通过内存 HTTP 拦截器验证，不连接后端。 */
package com.itranswarp.exchange.ui.web;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itranswarp.exchange.client.RestClient;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

class ProxyReadinessTest {
    /** 上游 503 从真实客户端传入 UI 过滤器后，浏览器仍收到 503 及明确业务错误。 */
    @Test
    void forwardsUnavailableAs503() throws Exception {
        var response = forward(503, "{\"error\":\"ENGINE_UNAVAILABLE\",\"data\":null,\"message\":\"恢复中\"}");
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("ENGINE_UNAVAILABLE"));
    }

    /** 普通业务拒绝仍返回 400，不被整体改成服务不可用。 */
    @Test
    void keepsBusinessErrorsAs400() throws Exception {
        var response = forward(400, "{\"error\":\"PARAMETER_INVALID\",\"data\":null,\"message\":\"参数错误\"}");
        assertEquals(400, response.getStatus());
    }

    /** 正常资产 JSON 经代理保持成功，说明门控错误处理没有破坏成功路径。 */
    @Test
    void forwardsSuccessfulAssets() throws Exception {
        String json = "{\"USD\":{\"available\":100,\"frozen\":0}}";
        var response = forward(200, json);
        assertEquals(200, response.getStatus());
        assertEquals(json, response.getContentAsString());
    }

    /** 真实过滤器装配拦截器客户端，只在内存中转发模拟响应。 */
    private MockHttpServletResponse forward(int status, String body) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RestClient client = new RestClient.Builder("http://localhost:8001").build(mapper);
        // 替换网络层，禁止本测试调用用户已经启动的业务应用。
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build()).build();
        ReflectionTestUtils.setField(client, "client", http);
        ProxyFilterRegistrationBean bean = new ProxyFilterRegistrationBean();
        bean.tradingApiClient = client;
        bean.objectMapper = mapper;
        bean.init();
        MockHttpServletResponse response = new MockHttpServletResponse();
        // API 代理自行处理请求，不应向后续过滤器转发。
        bean.getFilter().doFilter(new MockHttpServletRequest("GET", "/api/assets"), response,
                (request, result) -> fail("代理请求不应穿透到后续过滤器"));
        return response;
    }
}
