package brut.apktool.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 中文注释：Apktool Web 后端入口，使用 JDK 自带 HttpServer 提供轻量 API。
 * 生产环境建议放在反向代理之后，并进一步接入身份认证和持久化任务队列。
 */
public final class ApktoolWebServer {
    private static final long MAX_UPLOAD_BYTES = 512L * 1024L * 1024L;
    private static final int PORT = Integer.parseInt(System.getProperty("apktool.web.port", "8787"));
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Path workspace = Paths.get(System.getProperty("apktool.web.workspace", "web-work"))
        .toAbsolutePath().normalize();
    private final Path cliJar = resolveCliJar();

    public static void main(String[] args) throws Exception {
        new ApktoolWebServer().start();
    }

    private void start() throws IOException {
        Files.createDirectories(workspace);
        restoreTasks();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/api/health", exchange -> sendJson(exchange, 200,
            "{\"ok\":true,\"cli\":\"" + escape(cliJar.toString()) + "\"}"));
        server.createContext("/api/upload", new UploadHandler());
        server.createContext("/api/tasks", new TaskHandler());
        server.setExecutor(executor);
        server.start();
        System.out.println("Apktool Web API listening on http://127.0.0.1:" + PORT);
    }

    /** 中文注释：服务重启时恢复已有任务，并兼容直接放入 web-work 的解码项目目录。 */
    private void restoreTasks() throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.list(workspace)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                Path input = dir.resolve("input.apk");
                Path decoded = dir.resolve("decoded");
                // 中文注释：没有 input.apk 的目录视为用户手动放入的已解码项目。
                if (Files.isRegularFile(input) || Files.isDirectory(decoded) || Files.isDirectory(dir)) {
                    // 中文注释：优先识别工作区中与项目目录同名的 APK 文件，避免显示默认 input.apk。
                    Path siblingApk = workspace.resolve(dir.getFileName().toString() + ".apk");
                    String fallbackName = Files.isRegularFile(siblingApk) ? siblingApk.getFileName().toString() : dir.getFileName().toString();
                    String name = readProjectName(dir, fallbackName);
                    Task task = new Task(dir.getFileName().toString(), name, dir, input);
                    task.status = Files.isDirectory(decoded) || !Files.isRegularFile(input) ? "completed" : "uploaded";
                    task.log("已恢复历史任务，状态：" + task.status);
                    tasks.put(task.id, task);
                }
            });
        }
    }

    /** 中文注释：兼容从项目根目录和 apktool-web 模块目录启动两种工作目录。 */
    private static Path resolveCliJar() {
        String configured = System.getProperty("apktool.cli.jar");
        if (configured != null) return Paths.get(configured).toAbsolutePath().normalize();
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path direct = current.resolve("brut.apktool/apktool-cli/build/libs/apktool-cli.jar");
        Path parent = current.resolve("../brut.apktool/apktool-cli/build/libs/apktool-cli.jar").normalize();
        return Files.isRegularFile(direct) ? direct : parent;
    }

    /** 中文注释：处理原始 APK 二进制上传，避免引入额外 multipart 依赖。 */
    private final class UploadHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"仅支持 POST\"}"); return; }
            long length = exchange.getRequestBody().available();
            if (length > MAX_UPLOAD_BYTES) { sendJson(exchange, 413, "{\"error\":\"APK 文件超过 512MB 限制\"}"); return; }
            String name = query(exchange.getRequestURI(), "name");
            String safeName = normalizeApkName(name == null ? "uploaded.apk" : Paths.get(name).getFileName().toString());
            if (safeName == null) { sendJson(exchange, 400, "{\"error\":\"只允许上传 APK 或 APK.数字 文件\"}"); return; }
            String id = UUID.randomUUID().toString();
            Path dir = workspace.resolve(id).normalize();
            Files.createDirectories(dir);
            Path input = dir.resolve("input.apk");
            if (safeName.toLowerCase().endsWith(".apks")) {
                Path bundle = dir.resolve(safeName); copyLimited(exchange.getRequestBody(), bundle, MAX_UPLOAD_BYTES); extractBaseApk(bundle, input, dir);
            } else copyLimited(exchange.getRequestBody(), input, MAX_UPLOAD_BYTES);
            writeProjectMetadata(dir, safeName);
            Task task = new Task(id, safeName, dir, input);
            tasks.put(id, task);
            sendJson(exchange, 201, task.json());
        }
    }

    /** 中文注释：处理任务查询、解码和构建请求。 */
    private final class TaskHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            String[] parts = exchange.getRequestURI().getPath().split("/");
            if (parts.length == 3 && "GET".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 200, tasksJson()); return; }
            if (parts.length < 4) { sendJson(exchange, 404, "{\"error\":\"缺少任务 ID\"}"); return; }
            Task task = tasks.get(parts[3]);
            if (task == null) { sendJson(exchange, 404, "{\"error\":\"任务不存在\"}"); return; }
            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod()) && parts.length == 4) { deleteTask(task); sendJson(exchange, 200, "{\"ok\":true}"); return; }
            if (parts.length == 5 && "logs".equals(parts[4])) { sendJson(exchange, 200, task.logsJson()); return; }
            if (parts.length == 5 && "files".equals(parts[4])) { sendJson(exchange, 200, task.filesJson()); return; }
            if (parts.length == 5 && "content".equals(parts[4]) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) { task.contentJson(exchange.getRequestURI(), exchange); return; }
            if (parts.length == 5 && "bundle".equals(parts[4])) { sendJson(exchange, 200, task.bundleJson()); return; }
            if (parts.length == 5 && "decode".equals(parts[4])) { startProcess(exchange, task, false); return; }
            if (parts.length == 5 && "build".equals(parts[4])) { startProcess(exchange, task, true); return; }
            sendJson(exchange, 200, task.json());
        }
    }

    private void startProcess(HttpExchange exchange, Task task, boolean build) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, "{\"error\":\"仅支持 POST\"}"); return; }
        synchronized (task) {
            if (task.running) { sendJson(exchange, 409, "{\"error\":\"任务正在运行\"}"); return; }
            task.running = true;
            task.status = build ? "building" : "decoding";
            task.log(build ? "开始构建 APK" : "开始解码 APK");
        }
        String outputDir = build ? query(exchange.getRequestURI(), "output") : null;
        executor.submit(() -> runProcess(task, build, outputDir));
        sendJson(exchange, 202, task.json());
    }

    /** 中文注释：异步调用现有 Apktool CLI，并逐行收集标准输出作为前端日志流。 */
    private void runProcess(Task task, boolean build, String requestedOutputDir) {
        try {
            if (!Files.isRegularFile(cliJar)) throw new IOException("找不到 CLI JAR: " + cliJar);
            Path decoded = task.dir.resolve("decoded");
            List<String> command = new ArrayList<>();
            command.add("java"); command.add("-jar"); command.add(cliJar.toString());
            if (build) {
                String outputName = requestedOutputDir == null || requestedOutputDir.trim().isEmpty() ? "web-work" : requestedOutputDir;
                Path outputDir = "web-work".equalsIgnoreCase(outputName) ? workspace : task.dir.resolve(outputName).normalize();
                if (!outputDir.startsWith(workspace)) throw new IOException("输出目录必须位于当前项目目录内");
                Files.createDirectories(outputDir);
                String outputNameForFile = outputDir.equals(workspace) ? task.id + "-rebuilt.apk" : "rebuilt.apk";
                command.add("b"); command.add(decoded.toString()); command.add("-o"); command.add(outputDir.resolve(outputNameForFile).toString());
                task.log("输出目录：" + outputDir);
            }
            else {
                command.add("d");
                // 中文注释：检测 DEX 编号跳跃，避免异常命名导致 Smali 解码器中断整个任务。
                if (hasAbnormalDexNaming(task.input)) {
                    command.add("-s");
                    task.log("检测到 DEX 文件编号不连续，已自动启用兼容模式：保留原始 DEX，跳过 Smali 解码。");
                }
                command.add(task.input.toString()); command.add("-o"); command.add(decoded.toString());
            }
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (InputStream in = process.getInputStream()) {
                String line; ByteArrayOutputStream buffer = new ByteArrayOutputStream(); int value;
                while ((value = in.read()) != -1) { if (value == '\n') { line = buffer.toString("UTF-8").trim(); if (!line.isEmpty()) task.log(line); buffer.reset(); } else if (value != '\r') buffer.write(value); }
                if (buffer.size() > 0) task.log(buffer.toString("UTF-8").trim());
            }
            int exit = process.waitFor(); task.status = exit == 0 ? "completed" : "failed"; task.log("进程结束，退出码 " + exit);
        } catch (Exception error) { task.status = "failed"; task.log("错误：" + error.getMessage()); }
        finally { task.running = false; }
    }

    private static void copyLimited(InputStream in, Path target, long max) throws IOException {
        try (InputStream input = in; OutputStream out = Files.newOutputStream(target)) { byte[] data = new byte[8192]; long total = 0; int read; while ((read = input.read(data)) != -1) { total += read; if (total > max) throw new IOException("上传文件超过限制"); out.write(data, 0, read); } }
    }
    /** 中文注释：识别 classes4.dex 缺失但存在更高编号 DEX 的异常 APK。 */
    private static boolean hasAbnormalDexNaming(Path apk) {
        Set<Integer> numbers = new HashSet<>();
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.matches("classes\\d*\\.dex")) {
                    String number = name.substring(7, name.length() - 4);
                    numbers.add(number.isEmpty() ? 1 : Integer.parseInt(number));
                }
            }
        } catch (Exception ignored) { return false; }
        if (!numbers.contains(1)) return false;
        int max = Collections.max(numbers);
        for (int i = 1; i <= max; i++) if (!numbers.contains(i)) return true;
        return false;
    }
    private static boolean handleOptions(HttpExchange e) throws IOException { if (!"OPTIONS".equalsIgnoreCase(e.getRequestMethod())) return false; addCors(e.getResponseHeaders()); e.sendResponseHeaders(204, -1); e.close(); return true; }
    private static String query(URI uri, String key) { for (String pair : uri.getRawQuery() == null ? new String[0] : uri.getRawQuery().split("&")) { String[] kv = pair.split("=", 2); if (kv.length == 2 && key.equals(kv[0])) try { return URLDecoder.decode(kv[1], "UTF-8"); } catch (Exception ignored) { return null; } } return null; }
    private static void sendJson(HttpExchange e, int code, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); addCors(e.getResponseHeaders()); e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); e.sendResponseHeaders(code, bytes.length); try (OutputStream out = e.getResponseBody()) { out.write(bytes); } }
    /** 中文注释：删除任务目录及其上传 APK、反编译文件和元数据。 */
    private void deleteTask(Task task) throws IOException { tasks.remove(task.id); if (Files.exists(task.dir) && task.dir.startsWith(workspace)) { try (java.util.stream.Stream<Path> stream = Files.walk(task.dir)) { stream.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } } Files.deleteIfExists(workspace.resolve(task.id + ".apk")); }
    private static void addCors(Headers headers) { headers.set("Access-Control-Allow-Origin", "*"); headers.set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS"); headers.set("Access-Control-Allow-Headers", "Content-Type"); }
    // 中文注释：统一转义 JSON 字符串，并兼容异常情况下的空值。
    private static String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n"); }
    /** 中文注释：保存原始 APK 文件名，避免服务重启后退回显示 input.apk。 */
    private static void writeProjectMetadata(Path dir, String name) {
        try { Files.write(dir.resolve(".apktool-studio.json"), ("{\"name\":\"" + escape(name) + "\"}").getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
    }
    /** 中文注释：读取项目元数据，旧项目没有元数据时使用目录名称作为安全回退。 */
    private static String readProjectName(Path dir, String fallback) {
        try { String value = new String(Files.readAllBytes(dir.resolve(".apktool-studio.json")), StandardCharsets.UTF_8); java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(value); return matcher.find() ? matcher.group(1) : fallback; } catch (IOException ignored) { return fallback; }
    }
    /** 中文注释：返回后端当前任务列表，供前端项目文件页面使用。 */
    private String tasksJson() { tasks.values().removeIf(task -> !Files.isDirectory(task.dir)); StringBuilder out = new StringBuilder("{\"tasks\":["); boolean first = true; for (Task task : tasks.values()) { if (!first) out.append(','); first = false; out.append(task.json()); } return out.append("]}").toString(); }
    /** 中文注释：将下载工具产生的 app.apk.1、app.apk.2 等后缀统一还原为 APK 文件名。 */
    private static String normalizeApkName(String name) {
        String lower = name.toLowerCase();
        if (lower.matches(".+\\.apk\\.\\d+s")) return name.replaceFirst("(?i)\\.apk\\.\\d+s$", ".apks");
        if (lower.matches(".+\\.apk\\.\\d+")) return name.replaceFirst("(?i)\\.\\d+$", "");
        return lower.endsWith(".apk") || lower.endsWith(".apks") ? name : null;
    }
    /** 中文注释：解压 APKS 分包集合，提取 base.apk 作为 Apktool 的反编译入口。 */
    private static void extractBaseApk(Path bundle, Path input, Path dir) throws IOException {
        boolean found = false;
        try (ZipFile zip = new ZipFile(bundle.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) { ZipEntry entry = entries.nextElement(); if (entry.isDirectory()) continue; String name = entry.getName(); if (name.equalsIgnoreCase("base.apk") || name.endsWith("/base.apk")) { try (InputStream in = zip.getInputStream(entry)) { copyLimited(in, input, MAX_UPLOAD_BYTES); } found = true; } }
        }
        if (!found) throw new IOException("APKS 分包集合中未找到 base.apk");
    }

    /** 中文注释：内存中的任务状态，日志按顺序保存供前端轮询。 */
    private static final class Task {
        final String id, name; final Path dir, input; final List<String> logs = Collections.synchronizedList(new ArrayList<String>()); volatile String status = "uploaded"; volatile boolean running;
        Task(String id, String name, Path dir, Path input) { this.id=id; this.name=name; this.dir=dir; this.input=input; }
        /** 中文注释：将 Apktool CLI 的常见英文输出转换为中文，未知内容保持原样。 */
        void log(String message) { logs.add(translateLog(message)); }
        private static String translateLog(String message) {
            String value = message;
            value = value.replace("Warning: Apktool no longer supports 32-bit platforms.", "警告：Apktool 不再支持 32 位平台。");
            value = value.replace("Using Apktool", "使用 Apktool");
            value = value.replace("on input.apk with", "处理 input.apk，并使用");
            value = value.replace("threads", "个线程");
            value = value.replace("Loading resource table...", "正在加载资源表……");
            value = value.replace("Baksmaling classes.dex...", "正在反汇编 classes.dex……");
            value = value.replace("Decoding value resources...", "正在解码值资源……");
            value = value.replace("Loading resource table from file:", "正在从文件加载资源表：");
            value = value.replace("Decoding file resources...", "正在解码文件资源……");
            value = value.replace("Decoding AndroidManifest.xml with resources...", "正在解码带资源引用的 AndroidManifest.xml……");
            value = value.replace("Copying unknown files/dir...", "正在复制未知文件和目录……");
            value = value.replace("Building apk file...", "正在构建 APK 文件……");
            value = value.replace("Built apk into:", "APK 已构建至：");
            value = value.replace("Baksmaling", "正在反汇编");
            value = value.replace("Smaling", "正在汇编 Smali");
            return value;
        }
        String json() { return "{\"id\":\""+id+"\",\"name\":\""+escape(name)+"\",\"status\":\""+status+"\",\"running\":"+running+"}"; }
        String logsJson() { StringBuilder out=new StringBuilder("{\"logs\":["); synchronized(logs) { for(int i=0;i<logs.size();i++) { if(i>0) out.append(','); out.append('\"').append(escape(logs.get(i))).append('\"'); } } return out.append("]}").toString(); }
        /** 中文注释：读取项目内 APKS 压缩包的真实分包名称和大小。 */
        String bundleJson() throws IOException { StringBuilder out=new StringBuilder("{\"entries\":["); boolean first=true; try (java.util.stream.Stream<Path> stream=Files.list(dir)) { for (Path file : (Iterable<Path>)stream::iterator) if (file.getFileName().toString().toLowerCase().endsWith(".apks")) try (ZipFile zip=new ZipFile(file.toFile())) { java.util.Enumeration<? extends ZipEntry> entries=zip.entries(); while(entries.hasMoreElements()){ ZipEntry entry=entries.nextElement(); if(entry.isDirectory()) continue; if(!first) out.append(','); first=false; out.append("{\"name\":\"").append(escape(entry.getName())).append("\",\"size\":").append(entry.getSize()).append('}'); } } } return out.append("]}").toString(); }
        /** 中文注释：优先列出自动解码目录；对于手动放入 web-work 的项目，直接列出项目根目录。 */
        String filesJson() throws IOException { Path decoded = dir.resolve("decoded"); Path root = Files.isDirectory(decoded) ? decoded : dir; StringBuilder out = new StringBuilder("{\"files\":["); boolean first = true; try (java.util.stream.Stream<Path> stream = Files.walk(root)) { java.util.Iterator<Path> iterator = stream.filter(Files::isRegularFile).filter(file -> !file.getFileName().toString().equals(".apktool-studio.json")).sorted().iterator(); while (iterator.hasNext()) { Path file = iterator.next(); if (!first) out.append(','); first = false; out.append('\"').append(escape(root.relativize(file).toString().replace('\\','/'))).append('\"'); } } return out.append("]}").toString(); }
        // 中文注释：读取项目内文本文件供前端预览，严格限制路径和大小，避免访问项目目录之外的文件。
        void contentJson(URI uri, HttpExchange exchange) throws IOException { String raw = query(uri, "path"); if (raw == null || raw.trim().isEmpty()) { sendJson(exchange, 400, "{\"error\":\"缺少文件路径\"}"); return; } Path decoded = dir.resolve("decoded").normalize(); Path root = Files.isDirectory(decoded) ? decoded : dir; Path file = root.resolve(raw).normalize(); if (!file.startsWith(root) || !Files.isRegularFile(file)) { sendJson(exchange, 404, "{\"error\":\"文件不存在\"}"); return; } if (Files.size(file) > 20 * 1024 * 1024) { sendJson(exchange, 413, "{\"error\":\"文件过大，仅支持预览 20MB 以内文件\"}"); return; } String relative = root.relativize(file).toString().replace('\\','/'); String lower = file.getFileName().toString().toLowerCase(); String mime = lower.endsWith(".png") ? "image/png" : lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg" : lower.endsWith(".gif") ? "image/gif" : lower.endsWith(".webp") ? "image/webp" : ""; if (!mime.isEmpty()) { String base64 = java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(file)); sendJson(exchange, 200, "{\"path\":\"" + escape(relative) + "\",\"mime\":\"" + mime + "\",\"data\":\"" + base64 + "\"}"); return; } if (lower.endsWith(".dex")) { String info = "DEX 二进制文件\\n\\n该文件不能直接作为普通文本编辑。\\n可在项目中查看对应的 Smali 反编译目录，或使用 DEX 专用工具进行分析。\\n\\n文件大小：" + Files.size(file) + " 字节"; sendJson(exchange, 200, "{\"path\":\"" + escape(relative) + "\",\"content\":\"" + escape(info) + "\"}"); return; } String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8); sendJson(exchange, 200, "{\"path\":\"" + escape(relative) + "\",\"content\":\"" + escape(text) + "\"}"); }
    }
}
