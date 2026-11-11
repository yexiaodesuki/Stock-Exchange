/* 真实配置服务回归：六个应用获得集中配置，远程响应不能包含客户端私密配置项。 */
package com.itranswarp.exchange.config;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CONFIG_SERVER",matches="http://127\\.0\\.0\\.1:.+")
class SecuritiesConfigIntegrationTest {
    /** 配置中心断连必须在客户端配置阶段失败，不能悄悄使用默认数据库启动。 */
    @Test @EnabledIfEnvironmentVariable(named="SECURITIES_TEST_ROOT",matches=".+")
    void failsClosedWhenConfigServerIsUnavailable()throws Exception{
        var root=java.nio.file.Path.of(System.getenv("SECURITIES_TEST_ROOT"));
        var log=java.nio.file.Files.createTempFile("securities-config-unavailable-",".log");
        var builder=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx128m","-Dfile.encoding=UTF-8","-jar",root.resolve("trading-engine/target/trading-engine.jar").toString(),"--management.server.port=0");
        builder.environment().put("CONFIG_SERVER","http://127.0.0.1:1");
        builder.environment().put("SECURITIES_DB_PASSWORD","test-only-unreachable-config");
        builder.directory(root.toFile());
        var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(25,java.util.concurrent.TimeUnit.SECONDS));assertNotEquals(0,process.exitValue());String output=java.nio.file.Files.readString(log);assertTrue(output.contains("ConfigServerConfigData")||output.contains("configserver"),output);}
        finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}java.nio.file.Files.deleteIfExists(log);}
    }
    /** 对真实服务逐一查询，不以本地 YAML 内容推断客户端一定获得正确配置。 */
    @Test void distributesOnlyPublicSecuritiesConfiguration()throws Exception{
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        for(String app:new String[]{"engine","sequencer","quotation","api","push","ui"}){
            var response=http.send(HttpRequest.newBuilder(URI.create(System.getenv("CONFIG_SERVER")+"/securities-"+app+"/default")).timeout(Duration.ofSeconds(4)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("securities-v1")||response.body().contains("securities.v1"));
            assertFalse(response.body().contains("spring.datasource.password"));assertFalse(response.body().contains("securities.auth-key"));assertFalse(response.body().contains("securities.demo.passwords"));
            assertFalse(response.body().contains("/application-default.yml"),"禁止合并旧配置默认文件");
        }
    }
}
