/* 聊天端点校验：本地模式只允许回环，外部模式不降低 HTTPS 要求。 */
package com.itranswarp.exchange.service.assistant;

import java.net.URI;
import java.util.Set;

public final class ChatEndpoint {
  /** 工具类不实例化。 */
  private ChatEndpoint() {}

  /** 验证管理员配置，不接受 URL 中的凭据、查询参数或片段。 */
  public static URI validate(String endpoint, boolean local) {
    var uri = URI.create(endpoint);
    var host = uri.getHost();
    var scheme = uri.getScheme();
    if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
        (local ? !Set.of("localhost", "127.0.0.1", "[::1]").contains(host.toLowerCase(java.util.Locale.ROOT))
            || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
            : !"https".equalsIgnoreCase(scheme))) {
      throw new IllegalArgumentException(local ? "本地聊天模型必须使用回环地址" : "外部聊天模型必须使用 HTTPS");
    }
    return uri;
  }
}
