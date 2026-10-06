/* 共享业务错误码；引擎不可用错误沿完整代理链保持 HTTP 503 语义。 */
package com.itranswarp.exchange;

/**
 * ApiError constants.
 */
public enum ApiError {

    PARAMETER_INVALID,

    AUTH_SIGNIN_REQUIRED,

    AUTH_SIGNIN_FAILED,

    USER_CANNOT_SIGNIN,

    NO_ENOUGH_ASSET,

    ORDER_NOT_FOUND,

    OPERATION_TIMEOUT,

    ENGINE_UNAVAILABLE,

    INTERNAL_SERVER_ERROR;

}
