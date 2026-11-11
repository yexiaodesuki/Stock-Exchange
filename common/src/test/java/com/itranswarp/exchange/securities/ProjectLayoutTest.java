/* 源码布局回归：统一根包入口、专属装配与职责目录，防止包路径不匹配或再次混放。 */
package com.itranswarp.exchange.securities;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

class ProjectLayoutTest {
  private static final List<String> BUSINESS =
      List.of("push", "quotation", "trading-api", "trading-engine", "trading-sequencer", "ui");

  /** Maven 默认在 common 目录执行；隔离运行器可以显式指定项目根目录。 */
  private Path root() {
    String supplied = System.getenv("SECURITIES_TEST_ROOT");
    return supplied == null ? Path.of("..").toAbsolutePath().normalize() : Path.of(supplied);
  }

  /** 所有 Java 包均从 src/main/java 或 src/test/java 计算，不允许 IDE 获得截断包路径。 */
  @Test
  void everyPackageMatchesStandardSourceRoot() throws Exception {
    var modules = new java.util.ArrayList<>(BUSINESS);
    modules.addAll(List.of("common", "config"));
    var declaration = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
    for (String module : modules)
      for (String kind : List.of("main", "test")) {
        Path source = root().resolve(module + "/src/" + kind + "/java");
        if (!Files.isDirectory(source)) continue;
        try (var files = Files.walk(source)) {
          for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
            var match = declaration.matcher(Files.readString(file));
            assertTrue(match.find(), "缺少包声明：" + file);
            String expected =
                source.relativize(file.getParent()).toString().replace('\\', '.').replace('/', '.');
            assertEquals(expected, match.group(1), "包与源码路径不一致：" + file);
          }
        }
      }
  }

  /** 每个业务模块仅有证券入口，POM 不覆盖为深层源码根，入口类必须真实存在。 */
  @Test
  void businessModulesHaveOneResolvableMainClass() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    for (String module : BUSINESS) {
      var pom = factory.newDocumentBuilder().parse(root().resolve(module + "/pom.xml").toFile());
      for (String tag : List.of("sourceDirectory", "testSourceDirectory"))
        assertEquals(0, pom.getElementsByTagName(tag).getLength(), module + " 应使用 Maven 默认根目录");
      var mains = pom.getElementsByTagName("mainClass");
      assertEquals(1, mains.getLength(), module + " 必须声明唯一入口");
      String main = mains.item(0).getTextContent().trim();
      assertTrue(
          Files.isRegularFile(
              root().resolve(module + "/src/main/java/" + main.replace('.', '/') + ".java")),
          main);
      try (var files = Files.walk(root().resolve(module + "/src/main/java"))) {
        assertEquals(
            1L,
            files.filter(p -> p.getFileName().toString().endsWith("Application.java")).count(),
            module);
      }
    }
  }

  /** 使用标准 application.yml，依然强制访问配置中心；不再保留双套本地引导文件。 */
  @Test
  void applicationsUseOneStandardBootstrapConfiguration() throws Exception {
    for (String module : BUSINESS) {
      Path resources = root().resolve(module + "/src/main/resources");
      String yaml = Files.readString(resources.resolve("application.yml"));
      assertTrue(yaml.contains("configserver:${CONFIG_SERVER:"), module);
      assertTrue(yaml.contains("./build/.env.securities[.properties]"), module);
      try (var files = Files.list(resources)) {
        assertEquals(
            0L,
            files.filter(p -> p.getFileName().toString().matches("securities-.*\\.yml")).count(),
            module);
      }
    }
    assertTrue(Files.isRegularFile(root().resolve("config-repo/application.yml")));
    assertFalse(Files.exists(root().resolve("config-repo/securities")));
  }

  /** 所有启动入口位于相同根包；业务应用只导入专属装配，不持有 Bean 或扩大扫描范围。 */
  @Test
  void applicationEntrypointsStayThinAndConsistent() throws Exception {
    var configurations =
        java.util.Map.of(
            "trading-engine",
            "EngineConfiguration",
            "trading-sequencer",
            "SequencerConfiguration",
            "quotation",
            "QuotationConfiguration",
            "trading-api",
            "ApiConfiguration",
            "push",
            "PushConfiguration",
            "ui",
            "UIConfiguration");
    for (String module : BUSINESS) {
      Path base = root().resolve(module + "/src/main/java/com/itranswarp/exchange");
      try (var files = Files.walk(base)) {
        var entries =
            files.filter(p -> p.getFileName().toString().endsWith("Application.java")).toList();
        assertEquals(1, entries.size(), module);
        assertEquals(base, entries.get(0).getParent(), module + " 入口必须位于模块根包");
        String entry = Files.readString(entries.get(0));
        assertFalse(entry.contains("@Bean"), module + " 装配不应堆在入口");
        assertFalse(entry.contains("@ComponentScan"), module + " 不通过扩大扫描偶然注册控制器");
        assertTrue(entry.contains("@Import(" + configurations.get(module) + ".class)"), module);
      }
      String wiring =
          Files.readString(base.resolve("config/" + configurations.get(module) + ".java"));
      assertTrue(wiring.contains("Configuration(proxyBeanMethods = false)"), module);
      assertFalse(wiring.contains("static void main"), module);
    }
    assertTrue(
        Files.isRegularFile(
            root().resolve("config/src/main/java/com/itranswarp/exchange/ConfigApplication.java")));
  }

  /** 按 main 的 Service、业务包、web 习惯固定新增职责位置，不要求无关模块创建空目录。 */
  @Test
  void coordinatorsAndAdaptersHaveExplicitHomes() throws Exception {
    var required =
        java.util.Map.of(
            "trading-engine",
                List.of(
                    "TradingEngineService.java",
                    "snapshot/SnapshotRepository.java",
                    "messaging/KafkaHintConsumer.java",
                    "web/api/InternalTradingEngineApiController.java"),
            "trading-sequencer",
                List.of("sequencer/SequenceService.java", "sequencer/SequenceHandler.java"),
            "quotation",
                List.of(
                    "quotation/QuotationService.java",
                    "quotation/QuotationDbService.java",
                    "quotation/QuotationCacheService.java"),
            "trading-api",
                List.of(
                    "web/api/TradingApiController.java",
                    "web/json/PlainDecimalSerializer.java",
                    "service/SendEventService.java"),
            "push",
                List.of(
                    "push/PushService.java",
                    "push/web/PushServer.java",
                    "push/web/PushVerticle.java"),
            "ui",
                List.of(
                    "ui/web/MvcController.java",
                    "ui/web/UISecurityFilter.java",
                    "ui/service/UserService.java",
                    "ui/service/SessionService.java",
                    "ui/service/ApiProxyService.java",
                    "ui/demo/DemoBootstrap.java"));
    for (var module : required.entrySet()) {
      Path base = root().resolve(module.getKey() + "/src/main/java/com/itranswarp/exchange");
      for (String file : module.getValue())
        assertTrue(Files.isRegularFile(base.resolve(file)), module.getKey() + ":" + file);
      try (var files = Files.walk(base)) {
        assertFalse(
            files.anyMatch(p -> p.getFileName().toString().endsWith("Runtime.java")),
            module.getKey());
      }
    }
    assertFalse(
        Files.isRegularFile(
            root()
                .resolve(
                    "trading-engine/src/main/java/com/itranswarp/exchange/runtime/EngineRuntime.java")));
    assertFalse(
        Files.isRegularFile(
            root().resolve("ui/src/main/java/com/itranswarp/exchange/ui/SecuritiesWeb.java")));
  }
}
