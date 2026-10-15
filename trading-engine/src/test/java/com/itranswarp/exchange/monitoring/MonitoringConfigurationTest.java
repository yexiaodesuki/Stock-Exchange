/* 配置回归测试：解析真实集中配置与预置仪表盘，防止管理接口放开或数据源引用断裂。 */
package com.itranswarp.exchange.monitoring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringConfigurationTest {
    /** 校验集中配置保持独立端口，只开放健康和指标，并把引擎纳入就绪检查。 */
    @Test void managementConfiguration() throws Exception {
        Map<?, ?> root = yaml("config-repo/trading-engine.yml");
        var management = (Map<?, ?>) root.get("management");
        assertEquals("${ENGINE_MANAGEMENT_PORT:18002}", ((Map<?, ?>) management.get("server")).get("port"));
        var web = (Map<?, ?>) ((Map<?, ?>) management.get("endpoints")).get("web");
        assertEquals("health,prometheus", ((Map<?, ?>) web.get("exposure")).get("include"));
        var health = (Map<?, ?>) ((Map<?, ?>) management.get("endpoint")).get("health");
        assertEquals("never", health.get("show-details"));
        var groups = (Map<?, ?>) health.get("group");
        assertEquals("readinessState,engine", ((Map<?, ?>) groups.get("readiness")).get("include"));
    }

    /** 仪表盘可解析、面板编号唯一且数据源 UID 与 provisioning 一致。 */
    @Test void dashboardProvisioning() throws Exception {
        var dashboard = new ObjectMapper().readTree(Path.of("../build/monitoring/grafana/dashboards/engine.json").toFile());
        assertEquals("exchange-engine", dashboard.path("uid").asText());
        assertTrue(dashboard.path("description").asText().contains("交易引擎"));
        var identifiers = new HashSet<Integer>();
        for (var panel : dashboard.path("panels")) {
            assertTrue(identifiers.add(panel.path("id").asInt()));
            assertEquals("exchange-prometheus", panel.path("datasource").path("uid").asText());
            assertFalse(panel.path("targets").isEmpty());
        }
        assertEquals(13, identifiers.size());
        var source = yaml("build/monitoring/grafana/provisioning/datasources/prometheus.yml");
        var sources = (java.util.List<?>) source.get("datasources");
        assertEquals("exchange-prometheus", ((Map<?, ?>) sources.get(0)).get("uid"));
    }

    /** 读取真实仓库 YAML，不替换生产配置内容；测试在 Maven 模块目录中运行。 */
    private Map<?, ?> yaml(String relative) throws Exception {
        try (var input = Files.newInputStream(Path.of("..", relative))) {
            return new Yaml().load(input);
        }
    }
}
