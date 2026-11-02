/* 安全的模型错误类别：仅向页面传递错误码，不暴露响应原文、地址或密钥。 */
package com.itranswarp.exchange.service.assistant;
public final class AssistantModelException extends RuntimeException {
  private final String code;
  /** 记录可展示类别；原始供应商内容不进入异常消息。 */
  public AssistantModelException(String code){super(code);this.code=code;}
  /** 业务层据此选择明确的降级提示。 */
  public String code(){return code;}
}
