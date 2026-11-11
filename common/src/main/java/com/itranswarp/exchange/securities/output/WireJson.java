/* 对外 JSON 数值用精确十进制字符串，避免浏览器及中间 JsonNode 转换丢失整数/金额精度。 */
package com.itranswarp.exchange.securities.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

public final class WireJson {
    private static final ObjectMapper MAPPER=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    /** 工具类不实例化。 */
    private WireJson(){}
    /** 在副本上递归转换，不能修改核心或其他消费者的消息节点。 */
    public static JsonNode tree(JsonNode node){
        if(node.isNumber())return TextNode.valueOf(node.isFloatingPointNumber()?node.decimalValue().toPlainString():node.asText());
        if(node.isArray()){var array=MAPPER.createArrayNode();node.forEach(value->array.add(tree(value)));return array;}
        if(node.isObject()){var object=MAPPER.createObjectNode();node.fields().forEachRemaining(entry->object.set(entry.getKey(),tree(entry.getValue())));return object;}
        return node.deepCopy();
    }
    /** 对外转发前去除 JSON 数值精度隐患，布尔和 null 保持原类型。 */
    public static String text(String json){try{return MAPPER.writeValueAsString(tree(MAPPER.readTree(json)));}catch(Exception invalid){throw new IllegalArgumentException("无效公开 JSON",invalid);}}
}
