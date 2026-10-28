/* 开发与验收消息资源命名；测试只能使用独立前缀，禁止复用旧 Topic 或全局消费组。 */
package com.itranswarp.exchange.securities;

public record SecuritiesChannels(String namespace) {
    /** 仅允许正式证券前缀或明确的随机测试前缀。 */
    public SecuritiesChannels {
        if(namespace==null || !(namespace.equals("securities.v1") || namespace.matches("securities\\.test\\.[a-z0-9]{1,40}")))
            throw new IllegalArgumentException("证券消息命名空间无效");
    }
    /** Topic 种类仍由协议白名单检查，不允许注入其他应用 Topic。 */
    public String topic(String stage) { SecuritiesProtocol.topic(stage); return namespace+"."+stage; }
    /** 消费组采用稳定逻辑名称，测试前缀与开发组完全分离。 */
    public String group(String role) { SecuritiesProtocol.consumerGroup(role); return namespace+"."+role; }
}
