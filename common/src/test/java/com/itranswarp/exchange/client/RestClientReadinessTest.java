/* HTTP 客户端错误语义测试：用本地拦截器生成响应，不发起网络请求。 */
package com.itranswarp.exchange.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itranswarp.exchange.ApiError;
import com.itranswarp.exchange.ApiException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

class RestClientReadinessTest {
    /** 上游 503 应保留引擎不可用错误，而不是改成通用 500 错误。 */
    @Test
    void preservesUnavailableError() {
        RestClient client = client(503, "ENGINE_UNAVAILABLE");
        ApiException error = assertThrows(ApiException.class,
                () -> client.get(String.class, "/api/assets", null, null));
        assertEquals(ApiError.ENGINE_UNAVAILABLE, error.error.error());
        assertEquals("RECOVERING", error.error.data());
    }

    /** 原有参数错误仍按 400 错误体解析，兼容已有业务调用。 */
    @Test
    void preservesOrdinaryBusinessError() {
        ApiException error = assertThrows(ApiException.class,
                () -> client(400, "PARAMETER_INVALID").get(String.class, "/api/assets", null, null));
        assertEquals(ApiError.PARAMETER_INVALID, error.error.error());
    }

    /** 替换客户端网络执行为固定响应，其他请求和错误解析仍使用实际实现。 */
    private RestClient client(int status, String error) {
        RestClient client = new RestClient.Builder("http://localhost:8001").build(new ObjectMapper());
        // 直接返回共享格式错误，不访问真实应用端口。
        client.client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(ResponseBody.create("{\"error\":\"" + error
                        + "\",\"data\":\"RECOVERING\",\"message\":\"恢复中\"}",
                        MediaType.get("application/json"))).build()).build();
        return client;
    }
}
