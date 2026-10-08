/* 公共页脚回归：验证当前仓库链接、教程入口移除及页面继承，不连接业务服务或外部网站。 */
package com.itranswarp.exchange.ui.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class TemplateFooterTest {
    /** 源码入口指向当前仓库并保留新标签页行为，移除教程链接但保留许可证。 */
    @Test
    void footerUsesCurrentRepositoryWithoutTutorialEntry() throws IOException {
        String template = readTemplate("_base");
        assertTrue(template.contains("<a href=\"https://github.com/yexiaodesuki/Stock-Exchange\""
                + " target=\"_blank\">Source Code</a>"));
        assertFalse(template.contains("Learn How to Design"));
        assertFalse(template.contains("liaoxuefeng.com"));
        assertFalse(template.contains("github.com/michaelliao/warpexchange"));
        assertTrue(template.contains("<a href=\"https://www.gnu.org/licenses/gpl-3.0.txt\""
                + " target=\"_blank\">License</a>"));
    }

    /** 登录、注册和交易首页继续继承公共模板，因此统一使用更新后的页脚。 */
    @Test
    void allMainPagesInheritSharedFooter() throws IOException {
        for (String page : List.of("signin", "signup", "index")) {
            assertTrue(readTemplate(page).contains("{% extends(\"_base\") %}"), page);
        }
    }

    /** 从类路径读取实际模板资源，缺失时明确失败；关闭输入流，不访问网络。 */
    private String readTemplate(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/templates/" + name + ".html")) {
            assertNotNull(input, "模板资源不存在：" + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
