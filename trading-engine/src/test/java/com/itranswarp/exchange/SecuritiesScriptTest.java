/* Windows 脚本编码回归：两个 PowerShell 仅解析脚本，绝不执行启动、迁移或清理动作。 */
package com.itranswarp.exchange;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class SecuritiesScriptTest {
    /** 含中文的脚本必须带 UTF-8 BOM，Windows PowerShell 5.1 不能猜成 ANSI。 */
    @Test void scriptsUseUtf8BomAndParseInWindowsPowerShell()throws Exception{parse(Path.of(System.getenv("WINDIR"),"System32","WindowsPowerShell","v1.0","powershell.exe").toString());}
    /** 当前运行器找到的 PowerShell 7 也必须通过同一语法检查。 */
    @Test @EnabledIfEnvironmentVariable(named="SECURITIES_TEST_PWSH",matches=".+")
    void scriptsAlsoParseInPowerShellSeven()throws Exception{parse(System.getenv("SECURITIES_TEST_PWSH"));}
    /** 构建无副作用 Parser 调用，不用运行 infra 来冒充语法验证。 */
    private void parse(String executable)throws Exception{
        Path root=System.getenv("SECURITIES_TEST_ROOT")==null?Path.of("..").toAbsolutePath().normalize():Path.of(System.getenv("SECURITIES_TEST_ROOT"));
        var files=new ArrayList<Path>();try(var paths=Files.list(root.resolve("build/securities"))){paths.filter(p->p.toString().endsWith(".ps1")).sorted().forEach(files::add);}
        var literals=new ArrayList<String>();for(var file:files){byte[] bytes=Files.readAllBytes(file);assertTrue(bytes.length>3&&bytes[0]==(byte)0xef&&bytes[1]==(byte)0xbb&&bytes[2]==(byte)0xbf,"脚本缺 BOM："+file);literals.add("'"+file.toString().replace("'","''")+"'");}
        String command="$ErrorActionPreference='Stop'; foreach($file in @("+String.join(",",literals)+")){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile($file,[ref]$null,[ref]$errors)|Out-Null; if($errors){$errors|ForEach-Object{$_.Message}; exit 1}}; exit 0";
        var process=new ProcessBuilder(executable,"-NoProfile","-NonInteractive","-EncodedCommand",Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE))).redirectErrorStream(true).start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
    }
}
