/* 配置契约单元测试：错误中心或缺失标记不能进入业务初始化。 */
package com.itranswarp.exchange.securities;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class SecuritiesConfigContractTest {
    /** 缺配置与误连旧中心都明确拒绝，不能解释成空业务环境。 */
    @Test void rejectsMissingOrWrongContract(){var guard=new RequiredConfigEnvironment();var environment=new MockEnvironment().withProperty("spring.application.name","securities-engine");assertThrows(IllegalStateException.class,()->guard.postProcessEnvironment(environment,null));environment.setProperty("securities.config-contract","old");assertThrows(IllegalStateException.class,()->guard.postProcessEnvironment(environment,null));}
    /** 标记来自配置导入后才允许初始化；守卫顺序必须晚于 Config Data。 */
    @Test void acceptsImportedContract(){var guard=new RequiredConfigEnvironment();var environment=new MockEnvironment().withProperty("spring.application.name","securities-ui").withProperty("securities.config-contract","securities-v1");assertDoesNotThrow(()->guard.postProcessEnvironment(environment,null));assertTrue(guard.getOrder()>org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER);}
    /** 配置服务器自身不需要从自己取配置。 */
    @Test void doesNotGateConfigurationServer(){assertDoesNotThrow(()->new RequiredConfigEnvironment().postProcessEnvironment(new MockEnvironment().withProperty("spring.application.name","config-server"),null));}
}
