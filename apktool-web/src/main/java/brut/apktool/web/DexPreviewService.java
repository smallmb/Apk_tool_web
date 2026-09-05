package brut.apktool.web;

import com.android.apksig.ApkSigner;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.smali.smaliFlexLexer;
import com.android.tools.smali.smali.smaliParser;
import com.android.tools.smali.smali.smaliTreeWalker;
import com.google.gson.Gson;

import com.sun.net.httpserver.HttpExchange;

import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.TokenStream;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.antlr.runtime.tree.TreeNodeStream;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.plugins.input.ICodeLoader;
import jadx.api.plugins.input.data.IClassData;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.plugins.input.dex.DexInputPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 中文注释：DEX 编辑与实时预览服务，基于内嵌的 jadx 引擎。
 * 每个任务按需创建 JadxDecompiler（加载 decoded/original-dex 下的 classes*.dex），
 * 提供：类列表、Java/Smali 预览、Smali 编辑 -> 内存汇编 dex -> 替换类数据 -> 实时重反编译，
 * 以及把补丁类写回原始 dex / 重打包 APK 的导出能力。
 * 线程模型：单个任务的预览与补丁操作在该任务锁上串行执行。
 */
final class DexPreviewService {
	/** 中文注释：smali 汇编使用的 API 级别，足够覆盖新版本指令，汇编旧类也不受影响。 */
	private static final int API_LEVEL = 34;
	private static final long MAX_PATCH_BYTES = 4L * 1024L * 1024L;
	private static final Pattern ERROR_LINE_PATTERN = Pattern.compile("line (\\d+):\\d+");
	static final Pattern URL_EXTRACT = Pattern.compile("(?:https?|wss?)://[\\w\\-./%?&=#:~]+");
	private static final Gson GSON = new Gson();

	private final Map<String, TaskJadx> decompilers = new ConcurrentHashMap<>();

	/** 中文注释：按动作分发 /api/tasks/{id}/dex/{action} 请求，兜底捕获所有异常避免连接被直接重置。 */
	void handle(ApktoolWebServer.Task task, String action, HttpExchange exchange) throws IOException {
		try {
			switch (action) {
			case "classes":
				handleClasses(task, exchange);
				break;
			case "strings":
				handleStrings(task, exchange);
				break;
			case "patched":
				handlePatchedList(task, exchange);
				break;
			case "code":
				handleCode(task, exchange);
				break;
				case "patch":
					handlePatch(task, exchange);
					break;
				case "export":
					handleExport(task, exchange);
					break;
				default:
					sendJson(exchange, 404, "{\"error\":\"未知 DEX 操作\"}");
			}
		} catch (Throwable t) {
			t.printStackTrace();
			sendJson(exchange, 500, "{\"error\":\"DEX 服务内部错误：" + escape(String.valueOf(t)) + "\"}");
		}
	}

	/** 中文注释：任务删除或重新解码时释放 jadx 实例、补丁缓存与落盘的补丁 dex。 */
	void invalidate(ApktoolWebServer.Task task) {
		TaskJadx removed = decompilers.remove(task.id);
		if (removed != null) {
			removed.jadx.close();
		}
		deleteDir(task.dir.resolve(".studio-patches"));
	}

	/** 中文注释：返回当前任务已打补丁的类列表（内存 + 磁盘恢复），供前端标记与重启后恢复。 */
	private void handlePatchedList(ApktoolWebServer.Task task, HttpExchange exchange) throws IOException {
		List<String> types = new ArrayList<>();
		TaskJadx holder = decompilers.get(task.id);
		if (holder != null) {
			types.addAll(holder.patchedDex.keySet());
		}
		Path patchDir = task.dir.resolve(".studio-patches");
		if (Files.isDirectory(patchDir)) {
			try (Stream<Path> stream = Files.list(patchDir)) {
				for (Path file : (Iterable<Path>) stream.filter(p -> p.getFileName().toString().endsWith(".dex"))::iterator) {
					try {
						for (ClassDef classDef : readDexBytes(Files.readAllBytes(file)).getClasses()) {
							if (!types.contains(classDef.getType())) {
								types.add(classDef.getType());
							}
						}
					} catch (Exception ignored) {
					}
				}
			}
		}
		StringBuilder out = new StringBuilder("{\"types\":[");
		for (int i = 0; i < types.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append('"').append(escape(types.get(i))).append('"');
		}
		sendJson(exchange, 200, out.append("]}").toString());
	}

	/** 中文注释：补丁 dex 落盘到任务目录，服务重启后仍可导出与恢复标记。 */
	private void writePatchDex(ApktoolWebServer.Task task, String type, byte[] dexBytes) {
		try {
			Path dir = task.dir.resolve(".studio-patches");
			Files.createDirectories(dir);
			Files.write(dir.resolve(fileNameForType(type)), dexBytes);
		} catch (IOException ignored) {
		}
	}

	private static String fileNameForType(String type) {
		return type.replaceAll("[^A-Za-z0-9]", "_") + ".dex";
	}

	/** 中文注释：列出指定 dex 内的全部类（用 dexlib2 直接解析，无需 jadx）。 */
	private void handleClasses(ApktoolWebServer.Task task, HttpExchange exchange) throws IOException {
		ensureOriginalDex(task);
		Path dexFile = resolveDexFile(task, exchange);
		if (dexFile == null) {
			return;
		}
		byte[] dexBytes = Files.readAllBytes(dexFile);
		if (!isDexBytes(dexBytes)) {
			// 中文注释：加固/R8 常会塞入几字节的假 dex 占位文件，给出友好提示而不是内部错误。
			sendJson(exchange, 200, "{\"classes\":[],\"note\":\"该文件不是有效的 DEX（大小 " + dexBytes.length
					+ " 字节，通常为加固/R8 生成的占位文件）。请选择其他 classes*.dex 查看。\"}");
			return;
		}
		List<String> classes = new ArrayList<>();
		DexBackedDexFile dex = readDexBytes(dexBytes);
		for (ClassDef classDef : dex.getClasses()) {
			classes.add(classDef.getType());
		}
		Collections.sort(classes);
		StringBuilder out = new StringBuilder("{\"classes\":[");
		for (int i = 0; i < classes.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append('"').append(escape(classes.get(i))).append('"');
		}
		sendJson(exchange, 200, out.append("]}").toString());
	}

	/**
	 * 中文注释：枚举 dex 字符串池并按逆向语义分类（URL/密钥/域名/文案/其他），
	 * 一次遍历完成分类计数，再按关键字与类别过滤限量返回。这是逆向取信息的第一入口。
	 */
	private void handleStrings(ApktoolWebServer.Task task, HttpExchange exchange) throws IOException {
		ensureOriginalDex(task);
		Path dexFile = resolveDexFile(task, exchange);
		if (dexFile == null) {
			return;
		}
		String q = query(exchange.getRequestURI(), "q");
		String cat = query(exchange.getRequestURI(), "cat");
		if (cat == null || cat.isBlank()) {
			cat = "all";
		}
		String keyword = q == null ? "" : q.toLowerCase(Locale.ROOT).trim();
		byte[] dexBytes = Files.readAllBytes(dexFile);
		if (!isDexBytes(dexBytes)) {
			sendJson(exchange, 200, "{\"counts\":{},\"results\":[],\"note\":\"该文件不是有效的 DEX。\"}");
			return;
		}
		DexBackedDexFile dex = readDexBytes(dexBytes);
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String key : new String[] {"all", "url", "key", "domain", "text", "other"}) {
			counts.put(key, 0);
		}
		List<Map<String, Object>> results = new ArrayList<>();
		boolean truncated = false;
		for (String value : dex.getStringSection()) {
			if (value == null || value.length() < 4 || value.isBlank()) {
				continue;
			}
			String type = classifyString(value);
			counts.merge("all", 1, Integer::sum);
			counts.merge(type, 1, Integer::sum);
			// 中文注释：长文本里混入的 URL 只提取链接本体，避免整段代码/文案刷屏。
			if ("url".equals(type) && value.length() > 120) {
				Matcher urls = URL_EXTRACT.matcher(value);
				StringBuilder extracted = new StringBuilder();
				while (urls.find() && extracted.length() <= 800) {
					extracted.append(urls.group()).append("  ");
				}
				if (extracted.length() > 0) {
					value = extracted.toString().trim();
				}
			}
			if (results.size() >= 400) {
				truncated = true;
				continue;
			}
			if (!"all".equals(cat) && !type.equals(cat)) {
				continue;
			}
			if (!keyword.isEmpty() && !value.toLowerCase(Locale.ROOT).contains(keyword)) {
				continue;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("c", type);
			item.put("v", value.length() > 2000 ? value.substring(0, 2000) : value);
			results.add(item);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("counts", counts);
		out.put("results", results);
		out.put("truncated", truncated);
		sendJson(exchange, 200, GSON.toJson(out));
	}

	/** 中文注释：按逆向语义给字符串分类：URL、疑似密钥、疑似域名、中文文案、其他。 */
	static String classifyString(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		if (lower.contains("http://") || lower.contains("https://") || lower.startsWith("ws://") || lower.startsWith("wss://")) {
			return "url";
		}
		if (lower.contains("secret") || lower.contains("token") || lower.contains("passwd") || lower.contains("password")
				|| lower.contains("app_key") || lower.contains("appkey") || lower.contains("api_key") || lower.contains("apikey")) {
			return "key";
		}
		if (value.length() >= 16 && value.length() <= 64 && value.matches("[A-Fa-f0-9]+")
				|| value.length() >= 20 && value.length() <= 64 && value.matches("[A-Za-z0-9+/=_-]+") && value.matches(".*\\d.*") && value.matches(".*[A-Za-z].*")) {
			return "key";
		}
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			if (ch >= 0x4E00 && ch <= 0x9FFF) {
				return "text";
			}
		}
		if (value.matches("(?i)([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}")) {
			return "domain";
		}
		return "other";
	}

	/** 中文注释：反编译单个类，mode=java 返回 Java 源码，mode=smali 返回反汇编文本。 */
	private void handleCode(ApktoolWebServer.Task task, HttpExchange exchange) throws IOException {
		String cls = query(exchange.getRequestURI(), "cls");
		String mode = query(exchange.getRequestURI(), "mode");
		if (cls == null || cls.trim().isEmpty()) {
			sendJson(exchange, 400, "{\"error\":\"缺少 cls 参数\"}");
			return;
		}
		boolean smaliMode = "smali".equalsIgnoreCase(mode);
		ensureOriginalDex(task);
		TaskJadx holder;
		try {
			holder = getOrLoad(task);
		} catch (IOException e) {
			sendJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
			return;
		}
		synchronized (holder) {
			ClassNode node = holder.jadx.getRoot().resolveRawClass(toRawName(cls));
			if (node == null) {
				sendJson(exchange, 404, "{\"error\":\"dex 中未找到类 " + escape(cls) + "\"}");
				return;
			}
			String content = smaliMode ? node.getDisassembledCode() : node.decompile().getCodeStr();
			String jsonMode = smaliMode ? "smali" : "java";
			sendJson(exchange, 200, "{\"cls\":\"" + escape(cls) + "\",\"mode\":\"" + jsonMode
					+ "\",\"content\":\"" + escape(content) + "\"}");
		}
	}

	/** 中文注释：应用 smali 补丁：拆分段落 -> 逐类汇编 -> 替换 jadx 类数据 -> 返回新的 Java 预览。 */
	private void handlePatch(ApktoolWebServer.Task task, HttpExchange exchange) throws IOException {
		String cls = query(exchange.getRequestURI(), "cls");
		if (cls == null || cls.trim().isEmpty()) {
			sendJson(exchange, 400, "{\"error\":\"缺少 cls 参数\"}");
			return;
		}
		String smaliText = readBody(exchange, MAX_PATCH_BYTES);
		if (smaliText == null) {
			return;
		}
		ensureOriginalDex(task);
		TaskJadx holder;
		try {
			holder = getOrLoad(task);
		} catch (IOException e) {
			sendJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
			return;
		}
		long start = System.nanoTime();
		List<Section> sections = splitSections(smaliText);
		Map<String, byte[]> dexByType = new LinkedHashMap<>();
		List<String> errors = new ArrayList<>();
		Path tmpDir = null;
		try {
			tmpDir = Files.createTempDirectory("apktool-studio-smali");
			int index = 0;
			for (Section section : sections) {
				String fileName = "section" + (index++) + ".smali";
				Path smaliFile = tmpDir.resolve(fileName);
				Files.write(smaliFile, section.content.getBytes(StandardCharsets.UTF_8));
				try {
					byte[] dexBytes = assembleSmali(smaliFile, API_LEVEL);
					List<IClassData> datas = readDexClasses(dexBytes);
					if (datas.size() == 1) {
						dexByType.put(datas.get(0).getType(), dexBytes);
					} else {
						errors.add(fileName + ": 期望恰好一个类定义，实际 " + datas.size() + " 个");
					}
				} catch (Exception e) {
					errors.add(fileName + ": " + e.getMessage());
				}
			}
		} catch (IOException e) {
			sendJson(exchange, 500, "{\"error\":\"写入临时 smali 文件失败：" + escape(e.getMessage()) + "\"}");
			return;
		} finally {
			deleteDir(tmpDir);
		}
		if (!errors.isEmpty()) {
			List<Integer> errorLines = resolveErrorLines(errors, sections);
			StringBuilder out = new StringBuilder("{\"error\":\"").append(escape(String.join("\n", errors)))
					.append("\",\"errorLines\":[");
			for (int i = 0; i < errorLines.size(); i++) {
				if (i > 0) {
					out.append(',');
				}
				out.append(errorLines.get(i));
			}
			sendJson(exchange, 400, out.append("]}").toString());
			return;
		}
		synchronized (holder) {
			RootNode root = holder.jadx.getRoot();
			int patched = 0;
			try {
				for (Map.Entry<String, byte[]> entry : dexByType.entrySet()) {
					ClassNode node = root.resolveRawClass(toRawName(entry.getKey()));
					if (node == null) {
						throw new IOException("jadx 模型中未找到类 " + entry.getKey());
					}
					IClassData newData = readDexClasses(entry.getValue()).get(0);
					node.reloadClassData(newData);
					holder.patchedDex.put(entry.getKey(), entry.getValue());
					// 中文注释：补丁 dex 落盘，重启或刷新后标记与导出不丢。
					writePatchDex(task, entry.getKey(), entry.getValue());
					patched++;
				}
			} catch (Exception e) {
				sendJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
				return;
			}
			StringBuilder typesJson = new StringBuilder("[");
			boolean firstType = true;
			for (String type : dexByType.keySet()) {
				if (!firstType) {
					typesJson.append(',');
				}
				firstType = false;
				typesJson.append('"').append(escape(type)).append('"');
			}
			typesJson.append(']');
			ClassNode target = root.resolveRawClass(toRawName(cls));
			if (target == null) {
				sendJson(exchange, 404, "{\"error\":\"dex 中未找到类 " + escape(cls) + "\"}");
				return;
			}
			String java;
			try {
				// 中文注释：不依赖惰性创建的 JavaClass 包装器，直接在 ClassNode 上强制重新反编译。
				java = target.reloadCode().getCodeStr();
			} catch (Exception e) {
				sendJson(exchange, 200, "{\"ok\":true,\"patched\":" + patched + ",\"types\":" + typesJson
						+ ",\"java\":\"\",\"warn\":\"补丁已应用，但该类重新反编译失败：" + escape(e.getMessage()) + "\"}");
				return;
			}
			long timeMs = (System.nanoTime() - start) / 1_000_000;
			sendJson(exchange, 200, "{\"ok\":true,\"patched\":" + patched + ",\"types\":" + typesJson
					+ ",\"timeMs\":" + timeMs + ",\"java\":\"" + escape(java) + "\"}");
		}
	}

	/** 中文注释：把补丁类写回原始 dex（DexPool 重建），可选重打包 APK 并默认做调试签名。产物写入任务的 patched 目录。 */
	private void handleExport(ApktoolWebServer.Task task, HttpExchange exchange) throws IOException {
		String format = query(exchange.getRequestURI(), "format");
		boolean asApk = "apk".equalsIgnoreCase(format);
		boolean doSign = !"0".equals(query(exchange.getRequestURI(), "sign"));
		Map<String, byte[]> patchDex = new LinkedHashMap<>();
		TaskJadx holder = decompilers.get(task.id);
		if (holder != null) {
			patchDex.putAll(holder.patchedDex);
		}
		// 中文注释：服务重启后内存补丁丢失，从落盘目录恢复，保证导出能力不中断。
		Path patchDir = task.dir.resolve(".studio-patches");
		if (Files.isDirectory(patchDir)) {
			try (Stream<Path> stream = Files.list(patchDir)) {
				for (Path file : (Iterable<Path>) stream.filter(p -> p.getFileName().toString().endsWith(".dex"))::iterator) {
					try {
						byte[] bytes = Files.readAllBytes(file);
						for (ClassDef classDef : readDexBytes(bytes).getClasses()) {
							patchDex.putIfAbsent(classDef.getType(), bytes);
						}
					} catch (Exception ignored) {
					}
				}
			}
		}
		if (patchDex.isEmpty()) {
			sendJson(exchange, 400, "{\"error\":\"当前没有已应用的补丁，请先编辑 Smali 并应用\"}");
			return;
		}
		Path decoded = decodedDir(task);
		Path originalDexDir = decoded.resolve("original-dex");
		Path outDir = task.dir.resolve("patched");
		Files.createDirectories(outDir);
		List<String> written = new ArrayList<>();
		Map<String, ClassDef> patchedDefs = new LinkedHashMap<>();
		for (byte[] dexBytes : patchDex.values()) {
			DexBackedDexFile dex = readDexBytes(dexBytes);
			for (ClassDef classDef : dex.getClasses()) {
				patchedDefs.put(classDef.getType(), classDef);
			}
		}
		{
			List<Path> dexFiles = new ArrayList<>();
			try (Stream<Path> stream = Files.list(originalDexDir)) {
				stream.filter(file -> isDexName(file.getFileName().toString())).forEach(dexFiles::add);
			}
			dexFiles.sort(Comparator.comparing(path -> path.getFileName().toString()));
			for (Path dexFile : dexFiles) {
				byte[] original = Files.readAllBytes(dexFile);
				// 中文注释：跳过无效 dex（如加固/R8 产生的几字节假 dex），导出时原样保留。
				byte[] rebuilt = isDexBytes(original) ? rebuildDex(dexFile, patchedDefs) : original;
				Path outFile = outDir.resolve(dexFile.getFileName().toString());
				Files.write(outFile, rebuilt);
				written.add(outFile.toAbsolutePath().toString());
			}
			if (asApk) {
				if (!Files.isRegularFile(task.input)) {
					sendJson(exchange, 400, "{\"error\":\"任务没有原始 APK（input.apk），无法重打包\"}");
					return;
				}
				Map<String, byte[]> dexByName = new LinkedHashMap<>();
				for (Path file : dexFiles) {
					dexByName.put(file.getFileName().toString(), Files.readAllBytes(outDir.resolve(file.getFileName().toString())));
				}
				Path outApk = outDir.resolve("patched-unsigned.apk");
				repackApk(task.input, dexByName, outApk);
				if (doSign) {
					// 中文注释：内置调试密钥库自动 v1+v2 签名，产物可直接安装验证。
					Path signedApk = outDir.resolve("patched-signed.apk");
					try {
						signApk(outApk, signedApk, task.dir.getParent().resolve(".studio-debug.keystore"));
						written.add(signedApk.toAbsolutePath().toString());
					} catch (Exception e) {
						written.add(outApk.toAbsolutePath().toString());
						sendJson(exchange, 200, "{\"ok\":true,\"files\":[" + written.stream()
								.map(f -> "\"" + escape(f) + "\"").collect(java.util.stream.Collectors.joining(","))
								+ "],\"note\":\"APK 重打包成功，但自动签名失败（" + escape(String.valueOf(e)) + "），请使用未签名产物自行签名\"}");
						return;
					}
				} else {
					written.add(outApk.toAbsolutePath().toString());
				}
			}
		}
		StringBuilder out = new StringBuilder("{\"ok\":true,\"files\":[");
		for (int i = 0; i < written.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append('"').append(escape(written.get(i))).append('"');
		}
		out.append("],\"note\":\"").append(asApk
				? (doSign ? "APK 已用内置调试密钥签名（v1+v2），可直接安装验证；正式发布请用自有密钥重签"
					: "APK 未签名，安装前需自行签名")
				: "补丁 dex 已按原始文件名导出").append("\"}");
		sendJson(exchange, 200, out.toString());
	}

	private static final char[] KS_PASS = "apktoolstudio".toCharArray();

	/** 中文注释：首次签名时用 JDK keytool 生成本机调试密钥库（存于 web-work），之后复用。 */
	private void ensureDebugKeystore(Path ksFile) throws IOException, InterruptedException {
		if (Files.isRegularFile(ksFile)) {
			return;
		}
		Files.createDirectories(ksFile.getParent());
		String keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool").toString();
		Process process = new ProcessBuilder(keytool, "-genkeypair", "-keystore", ksFile.toString(),
				"-storetype", "PKCS12", "-storepass", "apktoolstudio", "-keypass", "apktoolstudio",
				"-alias", "studio", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
				"-dname", "CN=Apktool Studio Debug,O=ApktoolStudio,C=CN")
				.redirectErrorStream(true).start();
		process.getInputStream().readAllBytes();
		if (process.waitFor() != 0) {
			throw new IOException("生成调试密钥库失败，请检查 JDK keytool 可用性");
		}
	}

	/** 中文注释：用 apksig 对重打包 APK 做 v1+v2 调试签名。 */
	private void signApk(Path unsigned, Path signed, Path ksFile) throws Exception {
		ensureDebugKeystore(ksFile);
		java.security.KeyStore keyStore = java.security.KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(ksFile)) {
			keyStore.load(in, KS_PASS);
		}
		PrivateKey privateKey = (PrivateKey) keyStore.getKey("studio", KS_PASS);
		List<java.security.cert.X509Certificate> certificates = new ArrayList<>();
		for (java.security.cert.Certificate certificate : keyStore.getCertificateChain("studio")) {
			certificates.add((java.security.cert.X509Certificate) certificate);
		}
		ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder("apktool studio debug", privateKey, certificates).build();
		ApkSigner apkSigner = new ApkSigner.Builder(java.util.Collections.singletonList(signerConfig))
				.setInputApk(unsigned.toFile())
				.setOutputApk(signed.toFile())
				.setV1SigningEnabled(true)
				.setV2SigningEnabled(true)
				.build();
		apkSigner.sign();
	}

	/** 中文注释：旧任务没有 original-dex 时按需补提取，自动兼容历史项目。 */
	private void ensureOriginalDex(ApktoolWebServer.Task task) throws IOException {
		Path originalDexDir = decodedDir(task).resolve("original-dex");
		if (!Files.isDirectory(originalDexDir) && Files.isRegularFile(task.input)) {
			ApktoolWebServer.extractOriginalDex(task.input, originalDexDir);
		}
	}

	/** 中文注释：按需创建并加载任务的 jadx 实例（仅加载 classes*.dex，不解析资源）。 */
	private TaskJadx getOrLoad(ApktoolWebServer.Task task) throws IOException {
		TaskJadx existing = decompilers.get(task.id);
		if (existing != null) {
			return existing;
		}
		ensureOriginalDex(task);
		Path decoded = decodedDir(task);
		Path originalDexDir = decoded.resolve("original-dex");
		List<File> dexFiles = new ArrayList<>();
		try (Stream<Path> stream = Files.list(originalDexDir)) {
			stream.filter(file -> isDexName(file.getFileName().toString()))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.forEach(path -> dexFiles.add(path.toFile()));
		}
		if (dexFiles.isEmpty()) {
			throw new IOException("original-dex 目录中没有 classes*.dex 文件");
		}
		JadxArgs args = new JadxArgs();
		args.setInputFiles(dexFiles);
		JadxDecompiler jadx = new JadxDecompiler(args);
		jadx.load();
		TaskJadx holder = new TaskJadx(jadx);
		TaskJadx race = decompilers.putIfAbsent(task.id, holder);
		if (race != null) {
			jadx.close();
			return race;
		}
		return holder;
	}

	private static final class TaskJadx {
		final JadxDecompiler jadx;
		final Map<String, byte[]> patchedDex = new ConcurrentHashMap<>();

		TaskJadx(JadxDecompiler jadx) {
			this.jadx = jadx;
		}
	}

	private static final class Section {
		final int startLine;
		final String content;

		Section(int startLine, String content) {
			this.startLine = startLine;
			this.content = content;
		}
	}

	/** 中文注释：按 "###### Class" 头拆分多类 smali 文本（与 jadx Smali 视图格式一致）。 */
	private static List<Section> splitSections(String smaliText) {
		List<Section> sections = new ArrayList<>();
		String prefix = "###### Class ";
		int len = smaliText.length();
		int sectionStart = -1;
		int sectionStartLine = 0;
		int line = 0;
		int pos = 0;
		while (pos < len) {
			int lineEnd = smaliText.indexOf('\n', pos);
			int nextPos = lineEnd == -1 ? len : lineEnd + 1;
			if (smaliText.startsWith(prefix, pos)) {
				if (sectionStart != -1) {
					sections.add(new Section(sectionStartLine, smaliText.substring(sectionStart, pos)));
				}
				sectionStart = pos;
				sectionStartLine = line;
			}
			pos = nextPos;
			line++;
		}
		if (sectionStart != -1) {
			sections.add(new Section(sectionStartLine, smaliText.substring(sectionStart)));
		}
		if (sections.isEmpty()) {
			sections.add(new Section(0, smaliText));
		}
		return sections;
	}

	/** 中文注释：把汇编错误信息中的行号解析为合并文本中的编辑器行（0 基）。 */
	private static List<Integer> resolveErrorLines(List<String> errors, List<Section> sections) {
		List<Integer> lines = new ArrayList<>();
		Pattern sectionFilePattern = Pattern.compile("section(\\d+)\\.smali");
		for (String error : errors) {
			int sectionIdx = 0;
			Matcher fileMatcher = sectionFilePattern.matcher(error);
			if (fileMatcher.find()) {
				sectionIdx = Integer.parseInt(fileMatcher.group(1));
			}
			if (sectionIdx < 0 || sectionIdx >= sections.size()) {
				continue;
			}
			Matcher lineMatcher = ERROR_LINE_PATTERN.matcher(error);
			while (lineMatcher.find()) {
				lines.add(sections.get(sectionIdx).startLine + Integer.parseInt(lineMatcher.group(1)) - 1);
			}
		}
		return lines;
	}

	/** 中文注释：用 smali 库把单个 .smali 文件汇编为内存 dex 字节（移植自 jadx 的 SmaliUtils）。 */
	private static byte[] assembleSmali(Path smaliFile, int apiLevel) throws IOException {
		StringBuilder errors = new StringBuilder();
		File file = smaliFile.toFile();
		try (FileInputStream fis = new FileInputStream(file);
				InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
			smaliFlexLexer lexer = new smaliFlexLexer(reader, apiLevel);
			lexer.setSourceFile(file);
			CommonTokenStream tokens = new CommonTokenStream(lexer);
			CaptureParser parser = new CaptureParser(tokens, errors);
			parser.setVerboseErrors(false);
			parser.setAllowOdex(false);
			parser.setApiLevel(apiLevel);
			smaliParser.smali_file_return parseResult = parser.smali_file();
			if (parser.getNumberOfSyntaxErrors() > 0 || lexer.getNumberOfSyntaxErrors() > 0) {
				throw new IOException("Smali 语法错误：" + errors);
			}
			CommonTreeNodeStream treeStream = new CommonTreeNodeStream(parseResult.getTree());
			treeStream.setTokenStream(tokens);
			DexBuilder dexBuilder = new DexBuilder(Opcodes.forApi(apiLevel));
			CaptureTreeWalker dexGen = new CaptureTreeWalker(treeStream, errors);
			dexGen.setApiLevel(apiLevel);
			dexGen.setVerboseErrors(false);
			dexGen.setDexBuilder(dexBuilder);
			dexGen.smali_file();
			if (dexGen.getNumberOfSyntaxErrors() > 0) {
				throw new IOException("Smali 编译错误：" + errors);
			}
			MemoryDataStore dataStore = new MemoryDataStore();
			dexBuilder.writeTo(dataStore);
			return dataStore.getData();
		} catch (RecognitionException e) {
			throw new IOException("Smali 处理错误：" + errors, e);
		}
	}

	private static final class CaptureParser extends smaliParser {
		private final StringBuilder errors;

		CaptureParser(TokenStream input, StringBuilder errors) {
			super(input);
			this.errors = errors;
		}

		@Override
		public void emitErrorMessage(String msg) {
			errors.append('\n').append(msg);
		}
	}

	private static final class CaptureTreeWalker extends smaliTreeWalker {
		private final StringBuilder errors;

		CaptureTreeWalker(TreeNodeStream input, StringBuilder errors) {
			super(input);
			this.errors = errors;
		}

		@Override
		public void emitErrorMessage(String msg) {
			errors.append('\n').append(msg);
		}
	}

	/**
	 * 中文注释：从内存 dex 字节读取类数据。
	 * 注意：visitClasses 会复用同一个可变实例并在访问后移动读取偏移，
	 * 必须在 consumer 内立即 copy()，否则后续读取会得到错误数据。
	 */
	private static List<IClassData> readDexClasses(byte[] dexBytes) {
		DexInputPlugin dexPlugin = new DexInputPlugin();
		ICodeLoader loader = dexPlugin.loadDex(dexBytes, "patch.dex");
		List<IClassData> classes = new ArrayList<>();
		loader.visitClasses(data -> classes.add(data.copy()));
		return classes;
	}

	/** 中文注释：用 DexPool 重建原始 dex，把补丁类替换为新的类定义。 */
	private static byte[] rebuildDex(Path dexFile, Map<String, ClassDef> patchedDefs) throws IOException {
		byte[] dexBytes = Files.readAllBytes(dexFile);
		DexBackedDexFile origDex = readDexBytes(dexBytes);
		DexPool dexPool = new DexPool(origDex.getOpcodes());
		int replaced = 0;
		for (ClassDef classDef : origDex.getClasses()) {
			ClassDef patchedDef = patchedDefs.get(classDef.getType());
			if (patchedDef != null) {
				dexPool.internClass(patchedDef);
				replaced++;
			} else {
				dexPool.internClass(classDef);
			}
		}
		if (replaced == 0) {
			return dexBytes;
		}
		MemoryDataStore dataStore = new MemoryDataStore();
		dexPool.writeTo(dataStore);
		return dataStore.getData();
	}

	/** 中文注释：检查字节是否具有 DEX 魔数（dex\n），过滤加固包中的假 dex。 */
	static boolean isDexBytes(byte[] data) {
		return data.length >= 44 && data[0] == 'd' && data[1] == 'e' && data[2] == 'x' && data[3] == '\n';
	}

	/** 中文注释：把重打包 dex 写回 APK 副本（保持 stored 条目属性，产物未签名）。 */
	private static void repackApk(Path apkInput, Map<String, byte[]> dexByName, Path outApk) throws IOException {
		try (ZipFile zip = new ZipFile(apkInput.toFile());
				ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outApk))) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				byte[] data = dexByName.get(entry.getName());
				if (data == null) {
					try (InputStream in = zip.getInputStream(entry)) {
						data = in.readAllBytes();
					}
				}
				ZipEntry outEntry = new ZipEntry(entry.getName());
				if (entry.getMethod() == ZipEntry.STORED) {
					outEntry.setMethod(ZipEntry.STORED);
					outEntry.setSize(data.length);
					java.util.zip.CRC32 crc = new java.util.zip.CRC32();
					crc.update(data);
					outEntry.setCrc(crc.getValue());
				} else {
					outEntry.setMethod(ZipEntry.DEFLATED);
				}
				out.putNextEntry(outEntry);
				out.write(data);
				out.closeEntry();
			}
		}
	}

	static DexBackedDexFile readDexBytes(byte[] dexBytes) throws IOException {
		return DexBackedDexFile.fromInputStream(Opcodes.getDefault(), new ByteArrayInputStream(dexBytes));
	}

	/** 中文注释：解析任务内 dex 文件路径，校验必须位于任务目录内且是 classes*.dex。 */
	private Path resolveDexFile(ApktoolWebServer.Task task, HttpExchange exchange) throws IOException {
		String raw = query(exchange.getRequestURI(), "path");
		if (raw == null || raw.trim().isEmpty()) {
			sendJson(exchange, 400, "{\"error\":\"缺少 path 参数\"}");
			return null;
		}
		Path root = decodedDir(task);
		Path file = root.resolve(raw).normalize();
		if (!file.startsWith(root) || !Files.isRegularFile(file)) {
			sendJson(exchange, 404, "{\"error\":\"dex 文件不存在\"}");
			return null;
		}
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		if (!isDexName(name)) {
			sendJson(exchange, 400, "{\"error\":\"只支持 classes*.dex 文件\"}");
			return null;
		}
		return file;
	}

	private static Path decodedDir(ApktoolWebServer.Task task) {
		Path decoded = task.dir.resolve("decoded").normalize();
		return Files.isDirectory(decoded) ? decoded : task.dir;
	}

	private static boolean isDexName(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.matches("classes\\d*\\.dex");
	}

	/** 中文注释：jadx 的 rawClsMap 以点分形式为键（org.example.Foo），把 smali 描述符转换过去。 */
	private static String toRawName(String type) {
		String name = type;
		if (name.startsWith("L") && name.endsWith(";")) {
			name = name.substring(1, name.length() - 1);
		}
		return name.replace('/', '.');
	}

	private static String readBody(HttpExchange exchange, long max) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] data = new byte[8192];
		long total = 0;
		int read;
		try (InputStream in = exchange.getRequestBody()) {
			while ((read = in.read(data)) != -1) {
				total += read;
				if (total > max) {
					sendJson(exchange, 413, "{\"error\":\"补丁内容超过 4MB 限制\"}");
					return null;
				}
				buffer.write(data, 0, read);
			}
		}
		return buffer.toString("UTF-8");
	}

	private static void deleteDir(Path dir) {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> stream = Files.walk(dir)) {
			stream.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	private static void sendJson(HttpExchange exchange, int code, String body) throws IOException {
		ApktoolWebServer.sendJson(exchange, code, body);
	}

	private static String query(URI uri, String key) {
		return ApktoolWebServer.query(uri, key);
	}

	private static String escape(String value) {
		return ApktoolWebServer.escape(value);
	}
}
