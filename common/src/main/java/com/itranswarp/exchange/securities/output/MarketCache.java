/* 每证券缓存以单份版本化 JSON 原子替换；失败时上层回源，Redis 中不保存权威资金。 */
package com.itranswarp.exchange.securities.output;

import java.time.Duration;
import java.util.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import com.itranswarp.exchange.securities.SecuritiesChannels;
import com.itranswarp.exchange.util.JsonUtil;

public final class MarketCache implements AutoCloseable {
    private final RedisClient client; private final String prefix; private volatile StatefulRedisConnection<String,String> connection;
    // 序号按长度和字典序比较，不转成 Lua double，避免超过 2^53 后比较失真。
    private static final String PUT="local old=redis.call('HGET',KEYS[1],'version'); local v=ARGV[1]; if old and (#old>#v or (#old==#v and old>v)) then return 0 end; redis.call('HSET',KEYS[1],'version',v,'data',ARGV[2]); return 1";
    /** 延迟建立连接，使 API 在 Redis 不可用时仍能使用 MySQL/引擎回源。 */
    public MarketCache(String uri,String namespace){new SecuritiesChannels(namespace);prefix=namespace;var address=RedisURI.create(uri);address.setTimeout(Duration.ofSeconds(2));client=RedisClient.create(address);}
    /** 复用真实连接，断开时允许 Lettuce 重连，命令有短超时。 */
    private synchronized StatefulRedisConnection<String,String> connection(){if(connection==null)connection=client.connect();return connection;}
    /** 单证券单种文档原子替换，读者不会看到半个重建结果。 */
    public boolean put(long security,String kind,long version,Object data){return connection().sync().<Long>eval(PUT,ScriptOutputType.INTEGER,new String[]{key(security,kind)},Long.toString(version),JsonUtil.writeJson(data))==1;}
    /** 缓存不存在返回 empty；连接失败由调用方明确降级，不伪装成空行情。 */
    public Optional<String> get(long security,String kind){return Optional.ofNullable(connection().sync().hget(key(security,kind),"data"));}
    /** 私人票据只保存短时随机映射，值不写入日志。 */
    public String ticket(long user,long expires){if(user<=1||expires<=System.currentTimeMillis())throw new IllegalArgumentException("身份已过期");String value=UUID.randomUUID().toString().replace("-","");connection().sync().setex(prefix+":ticket:"+value,30,user+":"+expires);return value;}
    /** 原子消费一次性票据，不能被另一个连接重复使用。 */
    public Ticket consumeTicket(String ticket){if(ticket==null||!ticket.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("票据无效");String user=connection().sync().getdel(prefix+":ticket:"+ticket);if(user==null)throw new IllegalArgumentException("票据过期或已使用");var fields=user.split(":");var value=new Ticket(Long.parseLong(fields[0]),Long.parseLong(fields[1]));if(value.expiresAt()<=System.currentTimeMillis())throw new IllegalArgumentException("身份已过期");return value;}
    public record Ticket(long userId,long expiresAt){}
    /** 广播只使用经过验证的证券/用户路由，不订阅旧通知频道。 */
    public void publish(String route,String data){connection().sync().publish(prefix+":"+route,data);}
    /** 原始客户端只供同一前缀订阅器使用，不允许公开访问。 */
    public RedisClient client(){return client;}
    /** 返回稳定订阅前缀，Push 用于精确解析路由。 */
    public String prefix(){return prefix;}
    /** 缓存种类使用白名单，禁止跨证券/跨测试前缀注入 Redis 键。 */
    private String key(long security,String kind){if(security<=0||!Set.of("book","quotation").contains(kind))throw new IllegalArgumentException("缓存标识无效");return prefix+":"+security+":"+kind;}
    /** 仅关闭本应用连接，不删除缓存或数据卷。 */
    @Override public synchronized void close(){if(connection!=null)connection.close();client.shutdown();}
}
