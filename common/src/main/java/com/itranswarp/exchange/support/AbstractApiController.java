/* REST 业务错误处理：普通业务拒绝返回 400，引擎尚未就绪或不可达返回 503。 */
package com.itranswarp.exchange.support;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import com.itranswarp.exchange.ApiError;
import com.itranswarp.exchange.ApiErrorResponse;
import com.itranswarp.exchange.ApiException;

public abstract class AbstractApiController extends LoggerSupport {

    /** 保留共享错误响应结构，按错误类型选择状态码，不把恢复中伪装成普通参数错误。 */
    @ExceptionHandler(ApiException.class)
    @ResponseBody
    public ApiErrorResponse handleException(HttpServletResponse response, Exception ex) throws Exception {
        response.setContentType("application/json;charset=utf-8");
        ApiException apiEx = null;
        if (ex instanceof ApiException) {
            apiEx = (ApiException) ex;
        } else {
            apiEx = new ApiException(ApiError.INTERNAL_SERVER_ERROR, null, ex.getMessage());
        }
        response.setStatus(apiEx.error.error() == ApiError.ENGINE_UNAVAILABLE ? 503 : 400);
        return apiEx.error;
    }
}
