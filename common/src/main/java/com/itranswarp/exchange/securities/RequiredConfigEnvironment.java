/* 集中配置启动契约：在 Bean 初始化之前拒绝缺失或误连的配置，不让客户端回退其他数据库。 */
package com.itranswarp.exchange.securities;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public final class RequiredConfigEnvironment implements EnvironmentPostProcessor,Ordered {
    /** 必须在 Config Data 导入完成后校验，不能在远程请求之前误判。 */
    @Override public int getOrder(){return ConfigDataEnvironmentPostProcessor.ORDER+1;}
    /** 只保护证券应用，配置服务本身不依赖业务公共库；缺标记时禁止创建数据库连接。 */
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment,SpringApplication application){
        String name=environment.getProperty("spring.application.name","");
        if(name.startsWith("securities-")&&!"securities-v1".equals(environment.getProperty("securities.config-contract")))
            throw new IllegalStateException("configserver: 证券集中配置缺失或版本不匹配，禁止启动");
    }
}
