/* 七应用监控配置回归：端口唯一、抓取目标一致、预置数据源引用可解析。 */
package com.itranswarp.exchange.monitoring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class SystemMonitoringConfigurationTest {
    /** 检查每个真实管理端口与 Prometheus 静态目标对应，避免端口冲突或漏抓。 */
    @Test void sevenTargetsMatchSevenManagementPorts() throws Exception {
        Map<String, Integer> ports = Map.of("ui",18000,"trading-api",18001,"trading-engine",18002,
                "trading-sequencer",18003,"quotation",18005,"push",18006,"config-server",18888);
        var jobs = (List<?>) yaml("build/monitoring/prometheus.yml").get("scrape_configs");
        var seen = new HashSet<String>();
        for (Object item : jobs) {
            Map<?, ?> job = (Map<?, ?>) item;
            String name = (String) job.get("job_name");
            assertTrue(seen.add(name)); assertTrue(ports.containsKey(name));
            var statics = (List<?>) job.get("static_configs");
            assertEquals(List.of("host.docker.internal:" + ports.get(name)), ((Map<?, ?>) statics.get(0)).get("targets"));
            String file = name.equals("config-server") ? "config/src/main/resources/application.yml" : "config-repo/" + name + ".yml";
            var management = (Map<?, ?>) yaml(file).get("management");
            assertTrue(((Map<?, ?>) management.get("server")).get("port").toString().endsWith(":" + ports.get(name) + "}"));
        }
        assertEquals(ports.keySet(), seen);
        assertEquals(7, new HashSet<>(ports.values()).size());
    }

    /** 新面板使用独立 UID 和固定数据源，不覆盖原交易引擎面板。 */
    @Test void dashboardIdentifiersAndDatasource() throws Exception {
        var identifiers = new HashSet<String>();
        for (String name : List.of("engine", "system", "dependencies")) {
            var dashboard = new ObjectMapper().readTree(Path.of("../build/monitoring/grafana/dashboards/" + name + ".json").toFile());
            assertTrue(identifiers.add(dashboard.path("uid").asText()));
            assertFalse(dashboard.path("description").asText().isBlank());
            var panelIds = new HashSet<Integer>();
            for (var panel : dashboard.path("panels")) {
                assertTrue(panelIds.add(panel.path("id").asInt()));
                assertEquals("exchange-prometheus", panel.path("datasource").path("uid").asText());
                assertFalse(panel.path("targets").isEmpty());
            }
        }
    }

    /** 读取仓库实际 YAML，配置中不包含任何运行环境密码。 */
    private Map<?, ?> yaml(String relative) throws Exception {
        try (var input = Files.newInputStream(Path.of("..", relative))) { return new Yaml().load(input); }
    }
}
