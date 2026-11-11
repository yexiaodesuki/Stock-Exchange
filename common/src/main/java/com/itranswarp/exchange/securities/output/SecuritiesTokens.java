/* 证券专属短时身份签名；不兼容旧 BTC 令牌，不提供公开指定用户签发入口。 */
package com.itranswarp.exchange.securities.output;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SecuritiesTokens {
    private final byte[] key;
    /** 生产/开发密钥必须显式提供至少 32 字符，不能使用硬编码默认密码。 */
    public SecuritiesTokens(String key){if(key==null||key.length()<32)throw new IllegalArgumentException("证券认证密钥缺失或太短");this.key=key.getBytes(StandardCharsets.UTF_8);}
    /** 仅供可信登录服务和隔离测试使用，普通用户不得调用此内部签发方法。 */
    public String issue(long user,long expires){if(user<=1||expires<=System.currentTimeMillis()||expires>System.currentTimeMillis()+3600000)throw new IllegalArgumentException("身份字段无效");String payload=user+":"+expires+":securities";return Base64.getUrlEncoder().withoutPadding().encodeToString((payload+":"+sign(payload)).getBytes(StandardCharsets.UTF_8));}
    /** 严格验证用途、有效期、普通用户和常量时间签名比较。 */
    public long verify(String token){try{
        if(token==null||token.length()>512)throw new IllegalArgumentException("无效令牌");
        var parts=new String(Base64.getUrlDecoder().decode(token),StandardCharsets.UTF_8).split(":",-1);
        if(parts.length!=4||!parts[2].equals("securities"))throw new IllegalArgumentException("用途无效");
        long user=Long.parseLong(parts[0]),expires=Long.parseLong(parts[1]);
        if(user<=1||expires<=System.currentTimeMillis()||expires>System.currentTimeMillis()+3600000
                ||!MessageDigest.isEqual(parts[3].getBytes(StandardCharsets.US_ASCII),sign(parts[0]+":"+parts[1]+":securities").getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException("签名或有效期无效");
        return user;
    }catch(RuntimeException invalid){throw new IllegalArgumentException("证券身份无效");}}
    /** 票据不能延长原始登录身份寿命，先验证签名再读取到期时间。 */
    public long expires(String token){verify(token);return Long.parseLong(new String(Base64.getUrlDecoder().decode(token),StandardCharsets.UTF_8).split(":")[1]);}
    /** 使用 HMAC-SHA256，不使用可直接伪造的普通哈希签名。 */
    private String sign(String payload){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
