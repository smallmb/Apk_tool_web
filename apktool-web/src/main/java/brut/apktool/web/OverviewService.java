package brut.apktool.web;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.google.gson.Gson;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 中文注释：逆向信息总览服务。聚合解码产物中现成的信息，一页给出应用画像：
 * 基本信息、权限、四大组件、入口与 scheme、so 与加固识别、第三方 SDK 分布、DEX 构成、签名与体积。
 * 所有数据均来自本地解码目录与原始 APK，不做任何联网请求。
 */
final class OverviewService {
	/** 中文注释：常见加固/壳的特征 so 名称 -> 展示名。 */
	private static final Map<String, String> PACKER_SIGNS = new LinkedHashMap<>();
	/** 中文注释：常见第三方 SDK 前缀 -> 展示名。 */
	private static final Map<String, String> KNOWN_SDKS = new LinkedHashMap<>();
	private static final Pattern PKG_ATTR = Pattern.compile("<manifest[^>]*package=\"([^\"]+)\"");
	private static final Pattern VER_NAME = Pattern.compile("android:versionName=\"([^\"]*)\"");
	private static final Pattern VER_CODE = Pattern.compile("android:versionCode=\"([^\"]*)\"");
	private static final Pattern USES_SDK = Pattern.compile("<uses-sdk[^>]*>");
	private static final Pattern MIN_SDK = Pattern.compile("android:minSdkVersion=\"(\\d+)\"");
	private static final Pattern TARGET_SDK = Pattern.compile("android:targetSdkVersion=\"(\\d+)\"");
	private static final Pattern PERMISSION = Pattern.compile("<uses-permission[^>]*android:name=\"([^\"]+)\"");
	private static final Pattern COMPONENT = Pattern.compile("<(activity|activity-alias|service|receiver|provider)\\b([^>]*?)>");
	private static final Pattern COMP_NAME = Pattern.compile("android:name=\"([^\"]+)\"");
	private static final Pattern COMP_EXPORTED = Pattern.compile("android:exported=\"true\"");
	private static final Pattern SCHEME = Pattern.compile("android:scheme=\"([^\"]+)\"");
	private static final Pattern APP_LABEL = Pattern.compile("android:label=\"([^\"]*)\"");
	private static final Pattern STRING_RES = Pattern.compile("<string name=\"([^\"]+)\">([^<]*)</string>");
	private static final Pattern COLOR_RES = Pattern.compile("<color name=\"([^\"]+)\">([^<]*)</color>");
	private static final Gson GSON = new Gson();

	static {
		PACKER_SIGNS.put("libjiagu", "360加固");
		PACKER_SIGNS.put("libjiagu_art", "360加固");
		PACKER_SIGNS.put("libjiagu_x86", "360加固");
		PACKER_SIGNS.put("libDexHelper", "梆梆加固");
		PACKER_SIGNS.put("libSecShell", "梆梆加固");
		PACKER_SIGNS.put("libDexProtector", "DexProtector");
		PACKER_SIGNS.put("libexecmain", "爱加密");
		PACKER_SIGNS.put("libexec", "爱加密");
		PACKER_SIGNS.put("libnesec", "网秦加固");
		PACKER_SIGNS.put("libchaosvmp", "娜迦加固");
		PACKER_SIGNS.put("libddog", "娜迦加固");
		PACKER_SIGNS.put("libfdog", "娜迦加固");
		PACKER_SIGNS.put("libshella", "腾讯乐固");
		PACKER_SIGNS.put("libshellx", "腾讯乐固");
		PACKER_SIGNS.put("libtup", "腾讯乐固");
		PACKER_SIGNS.put("libtospro", "腾讯乐固");
		PACKER_SIGNS.put("libmobisec", "阿里聚安全");
		PACKER_SIGNS.put("libpreverify", "阿里聚安全");
		PACKER_SIGNS.put("libkwssgmain", "几维加固");
		PACKER_SIGNS.put("libkwsmain", "几维加固");

		KNOWN_SDKS.put("com.tencent.mm", "微信开放平台");
		KNOWN_SDKS.put("com.tencent.bugly", "Bugly 统计");
		KNOWN_SDKS.put("com.tencent.tauth", "QQ 互联");
		KNOWN_SDKS.put("com.umeng", "友盟");
		KNOWN_SDKS.put("com.alipay", "支付宝");
		KNOWN_SDKS.put("com.amap.api", "高德地图");
		KNOWN_SDKS.put("com.baidu", "百度");
		KNOWN_SDKS.put("io.flutter", "Flutter");
		KNOWN_SDKS.put("org.chromium", "Chromium");
		KNOWN_SDKS.put("com.huawei", "华为 HMS");
		KNOWN_SDKS.put("com.xiaomi", "小米");
		KNOWN_SDKS.put("com.meizu", "魅族");
		KNOWN_SDKS.put("cn.jpush", "极光推送");
		KNOWN_SDKS.put("cn.sharesdk", "ShareSDK");
		KNOWN_SDKS.put("com.getui", "个推");
		KNOWN_SDKS.put("com.igexin", "个推");
		KNOWN_SDKS.put("com.facebook", "Facebook");
		KNOWN_SDKS.put("com.paypal", "PayPal");
		KNOWN_SDKS.put("org.lsposed", "LSPosed 痕迹");
		KNOWN_SDKS.put("de.robv.android.xposed", "Xposed 痕迹");
	}

	/** 中文注释：处理 /api/tasks/{id}/overview，输出总览 JSON。 */
	void handle(ApktoolWebServer.Task task, HttpExchange exchange) throws IOException {
		ApktoolWebServer.sendJson(exchange, 200, GSON.toJson(buildOverview(task)));
	}

	/** 中文注释：聚合总览数据，同时供 JSON 接口与 Markdown 报告使用。 */
	Map<String, Object> buildOverview(ApktoolWebServer.Task task) throws IOException {
		Map<String, Object> out = new LinkedHashMap<>();
		Path decoded = decodedDir(task);
		Path manifest = decoded.resolve("AndroidManifest.xml");
		String manifestText = Files.isRegularFile(manifest) ? Files.readString(manifest, StandardCharsets.UTF_8) : "";
		Map<String, Object> basic = parseBasic(manifestText, decoded);
		out.put("basic", basic);
		out.put("manifest", parseManifest(manifestText));
		out.put("libs", parseLibs(decoded));
		out.put("dex", parseDexFiles(decoded));
		out.put("sdk", aggregateSdk(decoded, (String) basic.get("package")));
		out.put("sign", parseSign(task));
		return out;
	}

	/**
	 * 中文注释：生成逆向分析报告（Markdown 或 JSON）：
	 * 应用画像 + 权限/组件 + SDK + 加固 + 全量 URL 与疑似密钥清单，作为信息获取场景的交付物。
	 */
	void handleReport(ApktoolWebServer.Task task, HttpExchange exchange, String format) throws IOException {
		Map<String, Object> data = buildOverview(task);
		Map<String, Object> basic = cast(data.get("basic"));
		Map<String, Object> manifest = cast(data.get("manifest"));
		Map<String, Object> libs = cast(data.get("libs"));
		Map<String, Object> sign = cast(data.get("sign"));
		if ("json".equalsIgnoreCase(format)) {
			byte[] body = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
			sendDownload(exchange, "analysis-" + safe(basic.get("package")) + ".json", "application/json; charset=utf-8", body);
			return;
		}
		StringBuilder md = new StringBuilder();
		md.append("# 应用逆向分析报告\n\n");
		md.append("- 应用名：").append(basic.get("appName")).append('\n');
		md.append("- 包名：").append(basic.get("package")).append('\n');
		md.append("- 版本：").append(basic.get("versionName")).append(" (").append(basic.get("versionCode")).append(")\n");
		md.append("- SDK：min ").append(basic.get("minSdk")).append(" → target ").append(basic.get("targetSdk")).append('\n');
		md.append("- 入口：").append(basic.get("entryActivity")).append('\n');
		md.append("- 加固：").append(libs.get("packer") == null ? "未识别到特征" : libs.get("packer")).append('\n');
		md.append("- APK 体积：").append(sign.get("apkSize")).append(" 字节；v1 签名痕迹：").append(sign.get("v1")).append("\n\n");
		md.append("## 权限清单\n");
		for (Object p : list(manifest.get("permissions"))) {
			md.append("- ").append(p).append('\n');
		}
		md.append("\n## exported 组件\n");
		for (Object p : list(manifest.get("exported"))) {
			md.append("- ").append(p).append('\n');
		}
		md.append("\n## Deep Link scheme\n");
		for (Object p : list(manifest.get("schemes"))) {
			md.append("- ").append(p).append('\n');
		}
		md.append("\n## 第三方 SDK / 包分布（按类数量）\n\n| 包前缀 | 识别 | 类数量 |\n| --- | --- | --- |\n");
		for (Object item : list(data.get("sdk"))) {
			Map<String, Object> sdk = cast(item);
			md.append("| ").append(sdk.get("prefix")).append(" | ").append(sdk.get("name") == null ? "" : sdk.get("name")).append(" | ").append(sdk.get("classes")).append(" |\n");
		}
		md.append("\n## Native 库\n");
		for (Object item : list(libs.get("sos"))) {
			Map<String, Object> so = cast(item);
			md.append("- ").append(so.get("abi")).append("/").append(so.get("name")).append("（").append(so.get("size")).append(" 字节）\n");
		}
		md.append("\n## DEX 构成\n");
		for (Object item : list(data.get("dex"))) {
			Map<String, Object> dex = cast(item);
			md.append("- ").append(dex.get("name")).append("：").append(dex.get("classes")).append(" 类 / ").append(dex.get("size")).append(" 字节").append(Boolean.TRUE.equals(dex.get("valid")) ? "" : "（无效占位）").append('\n');
		}
		md.append("\n## URL 清单（dex 字符串池提取）\n");
		int[] urlCount = {0};
		int[] keyCount = {0};
		for (Object item : list(data.get("dex"))) {
			Map<String, Object> dex = cast(item);
			if (!Boolean.TRUE.equals(dex.get("valid"))) {
				continue;
			}
			Path dexFile = decodedDir(task).resolve("original-dex").resolve(String.valueOf(dex.get("name")));
			if (!Files.isRegularFile(dexFile)) {
				continue;
			}
			DexBackedDexFile pool = DexPreviewService.readDexBytes(Files.readAllBytes(dexFile));
			for (String value : pool.getStringSection()) {
				if (value == null || value.length() < 5) {
					continue;
				}
				String type = DexPreviewService.classifyString(value);
				if ("url".equals(type) && urlCount[0] < 500) {
					if (value.length() > 120) {
						java.util.regex.Matcher urls = DexPreviewService.URL_EXTRACT.matcher(value);
						StringBuilder extracted = new StringBuilder();
						while (urls.find() && extracted.length() <= 800) {
							extracted.append(urls.group()).append("  ");
						}
						if (extracted.length() > 0) {
							value = extracted.toString().trim();
						}
					}
					md.append("- ").append(value.replace("\n", " ").replace("|", "\\|")).append('\n');
					urlCount[0]++;
				}
			}
		}
		if (urlCount[0] == 0) {
			md.append("- 无\n");
		}
		md.append("\n## 疑似密钥清单\n");
		for (Object item : list(data.get("dex"))) {
			Map<String, Object> dex = cast(item);
			if (!Boolean.TRUE.equals(dex.get("valid"))) {
				continue;
			}
			Path dexFile = decodedDir(task).resolve("original-dex").resolve(String.valueOf(dex.get("name")));
			if (!Files.isRegularFile(dexFile)) {
				continue;
			}
			DexBackedDexFile pool = DexPreviewService.readDexBytes(Files.readAllBytes(dexFile));
			for (String value : pool.getStringSection()) {
				if (value == null || value.length() < 5) {
					continue;
				}
				String type = DexPreviewService.classifyString(value);
				if ("key".equals(type) && keyCount[0] < 300) {
					md.append("- `").append(value.replace("`", "'").replace("\n", " ")).append("`\n");
					keyCount[0]++;
				}
			}
		}
		if (keyCount[0] == 0) {
			md.append("- 无\n");
		}
		byte[] body = md.toString().getBytes(StandardCharsets.UTF_8);
		sendDownload(exchange, "analysis-" + safe(basic.get("package")) + ".md", "text/markdown; charset=utf-8", body);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Object value) {
		return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return value instanceof List ? (List<Object>) value : new ArrayList<>();
	}

	private static String safe(Object value) {
		String name = String.valueOf(value == null ? "app" : value);
		return name.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private void sendDownload(HttpExchange exchange, String filename, String contentType, byte[] body) throws IOException {
		ApktoolWebServer.addCors(exchange.getResponseHeaders());
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	/** 中文注释：基本信息：包名、版本、SDK 区间、应用名、入口 Activity。apktool 3.x 会把版本与 SDK 移入 apktool.yml，缺失时回退读取。 */
	private Map<String, Object> parseBasic(String manifestText, Path decoded) throws IOException {
		Map<String, Object> basic = new LinkedHashMap<>();
		basic.put("package", group(PKG_ATTR, manifestText));
		String versionName = group(VER_NAME, manifestText);
		String versionCode = group(VER_CODE, manifestText);
		Integer minSdk = num(group(MIN_SDK, manifestText));
		Integer targetSdk = num(group(TARGET_SDK, manifestText));
		Path yml = decoded.resolve("apktool.yml");
		if (Files.isRegularFile(yml)) {
			String ymlText = Files.readString(yml, StandardCharsets.UTF_8);
			if (versionName == null) {
				versionName = ymlGroup(ymlText, "versionName");
			}
			if (versionCode == null) {
				versionCode = ymlGroup(ymlText, "versionCode");
			}
			if (minSdk == null) {
				minSdk = num(ymlGroup(ymlText, "minSdkVersion"));
			}
			if (targetSdk == null) {
				targetSdk = num(ymlGroup(ymlText, "targetSdkVersion"));
			}
		}
		basic.put("versionName", versionName);
		basic.put("versionCode", versionCode);
		basic.put("minSdk", minSdk);
		basic.put("targetSdk", targetSdk);
		String label = group(APP_LABEL, manifestText);
		if (label != null && label.startsWith("@string/")) {
			label = resolveStringRes(decoded, label.substring("@string/".length()));
		}
		basic.put("appName", label);
		basic.put("entryActivity", findEntryActivity(manifestText));
		return basic;
	}

	/** 中文注释：从 apktool.yml 提取 key: value 形式的标量（容忍单引号）。 */
	private String ymlGroup(String ymlText, String key) {
		Matcher matcher = Pattern.compile("(?m)^\\s*" + key + ":\\s*'?(.*?)'?\\s*$").matcher(ymlText);
		return matcher.find() ? matcher.group(1) : null;
	}

	/** 中文注释：权限、组件统计、exported 组件、scheme、安全标志。 */
	private Map<String, Object> parseManifest(String text) {
		Map<String, Object> manifest = new LinkedHashMap<>();
		List<String> permissions = new ArrayList<>();
		Matcher pm = PERMISSION.matcher(text);
		while (pm.find()) {
			permissions.add(pm.group(1));
		}
		manifest.put("permissions", permissions);
		Map<String, Integer> compCount = new LinkedHashMap<>();
		List<String> exported = new ArrayList<>();
		Matcher cm = COMPONENT.matcher(text);
		while (cm.find()) {
			String kind = cm.group(1);
			compCount.merge(kind, 1, Integer::sum);
			String attrs = cm.group(2);
			Matcher name = COMP_NAME.matcher(attrs);
			if (COMP_EXPORTED.matcher(attrs).find() && name.find() && exported.size() < 40) {
				exported.add(kind + ": " + name.group(1));
			}
		}
		manifest.put("components", compCount);
		manifest.put("exported", exported);
		List<String> schemes = new ArrayList<>();
		Matcher sm = SCHEME.matcher(text);
		while (sm.find() && schemes.size() < 30) {
			if (!schemes.contains(sm.group(1))) {
				schemes.add(sm.group(1));
			}
		}
		manifest.put("schemes", schemes);
		Map<String, Object> flags = new LinkedHashMap<>();
		flags.put("debuggable", text.contains("android:debuggable=\"true\""));
		flags.put("allowBackup", text.contains("android:allowBackup=\"true\""));
		flags.put("networkSecurityConfig", text.contains("android:networkSecurityConfig"));
		manifest.put("flags", flags);
		return manifest;
	}

	/** 中文注释：lib 目录下的 abi 与 so 清单，并按特征名识别加固壳。 */
	private Map<String, Object> parseLibs(Path decoded) throws IOException {
		Map<String, Object> libs = new LinkedHashMap<>();
		List<Map<String, Object>> sos = new ArrayList<>();
		List<String> abis = new ArrayList<>();
		String packer = null;
		List<String> packerSigns = new ArrayList<>();
		Path libDir = decoded.resolve("lib");
		if (Files.isDirectory(libDir)) {
			try (Stream<Path> stream = Files.list(libDir)) {
				stream.filter(Files::isDirectory).forEach(dir -> abis.add(dir.getFileName().toString()));
			}
			Collections.sort(abis);
			for (String abi : abis) {
				Path abiDir = libDir.resolve(abi);
				try (Stream<Path> stream = Files.list(abiDir)) {
					for (Path so : (Iterable<Path>) stream.filter(file -> file.getFileName().toString().endsWith(".so"))::iterator) {
						String name = so.getFileName().toString();
						Map<String, Object> item = new LinkedHashMap<>();
						item.put("name", name);
						item.put("abi", abi);
						item.put("size", Files.size(so));
						sos.add(item);
						String lower = name.toLowerCase(Locale.ROOT);
						for (Map.Entry<String, String> sign : PACKER_SIGNS.entrySet()) {
							if (lower.contains(sign.getKey()) && packerSigns.isEmpty()) {
								packerSigns.add(sign.getValue() + " (" + name + ")");
							}
						}
					}
				}
			}
		}
		if (!packerSigns.isEmpty()) {
			packer = packerSigns.get(0);
		}
		libs.put("abis", abis);
		libs.put("sos", sos);
		libs.put("packer", packer);
		libs.put("packerSigns", packerSigns);
		return libs;
	}

	/** 中文注释：DEX 构成：每个 dex 的类数量与体积，并标记无效的占位 dex。 */
	private List<Map<String, Object>> parseDexFiles(Path decoded) throws IOException {
		List<Map<String, Object>> dexList = new ArrayList<>();
		Path originalDexDir = decoded.resolve("original-dex");
		if (!Files.isDirectory(originalDexDir)) {
			return dexList;
		}
		try (Stream<Path> stream = Files.list(originalDexDir)) {
			List<Path> files = new ArrayList<>();
			stream.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).matches("classes\\d*\\.dex"))
					.forEach(files::add);
			files.sort(Comparator.comparing(path -> path.getFileName().toString()));
			for (Path file : files) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("name", file.getFileName().toString());
				item.put("size", Files.size(file));
				byte[] bytes = Files.readAllBytes(file);
				if (bytes.length >= 44 && bytes[0] == 'd' && bytes[1] == 'e' && bytes[2] == 'x' && bytes[3] == '\n') {
					DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), new ByteArrayInputStream(bytes));
					int classes = 0;
					for (@SuppressWarnings("unused") ClassDef unused : dex.getClasses()) {
						classes++;
					}
					item.put("classes", classes);
					item.put("valid", true);
				} else {
					item.put("classes", 0);
					item.put("valid", false);
					item.put("note", "占位/无效 dex");
				}
				dexList.add(item);
			}
		}
		return dexList;
	}

	/** 中文注释：按包前缀聚合第三方 SDK 分布（排除应用自身包名），附带常见 SDK 识别。 */
	private List<Map<String, Object>> aggregateSdk(Path decoded, String ownPackage) throws IOException {
		Map<String, Integer> counts = new HashMap<>();
		Path originalDexDir = decoded.resolve("original-dex");
		if (Files.isDirectory(originalDexDir)) {
			try (Stream<Path> stream = Files.list(originalDexDir)) {
				for (Path file : (Iterable<Path>) stream.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).matches("classes\\d*\\.dex"))::iterator) {
					if (Files.size(file) <= 1024) {
						continue;
					}
					DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), new ByteArrayInputStream(Files.readAllBytes(file)));
					for (ClassDef classDef : dex.getClasses()) {
						String type = classDef.getType();
						if (!type.startsWith("L")) {
							continue;
						}
						String path = type.substring(1, type.length() - 1);
						if (ownPackage != null && !ownPackage.isEmpty() && path.startsWith(ownPackage.replace('.', '/'))) {
							continue;
						}
						String[] parts = path.split("/");
						String key;
						if (parts.length >= 2 && ("com".equals(parts[0]) || "org".equals(parts[0]) || "cn".equals(parts[0]) || "net".equals(parts[0]) || "io".equals(parts[0]))) {
							key = parts[0] + "." + parts[1];
						} else {
							key = parts[0];
						}
						if (key.length() <= 2) {
							continue;
						}
						counts.merge(key, 1, Integer::sum);
					}
				}
			}
		}
		List<Map<String, Object>> result = new ArrayList<>();
		counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(18)
				.forEach(entry -> {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("prefix", entry.getKey());
					item.put("classes", entry.getValue());
					String known = null;
					for (Map.Entry<String, String> knownEntry : KNOWN_SDKS.entrySet()) {
						if (entry.getKey().startsWith(knownEntry.getKey())) {
							known = knownEntry.getValue();
							break;
						}
					}
					item.put("name", known);
					result.add(item);
				});
		return result;
	}

	/** 中文注释：原始 APK 的体积、v1/v2/v3 签名方案与证书详情（解析 META-INF 证书）。 */
	private Map<String, Object> parseSign(ApktoolWebServer.Task task) throws IOException {
		Map<String, Object> sign = new LinkedHashMap<>();
		long apkSize = 0;
		List<String> schemes = new ArrayList<>();
		List<String> signFiles = new ArrayList<>();
		List<Map<String, Object>> certs = new ArrayList<>();
		if (Files.isRegularFile(task.input)) {
			apkSize = Files.size(task.input);
			try (ZipFile zip = new ZipFile(task.input.toFile())) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String upper = entry.getName().toUpperCase(Locale.ROOT);
					if (upper.startsWith("META-INF/") && (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) {
						signFiles.add(entry.getName());
						try (InputStream in = zip.getInputStream(entry)) {
							java.security.cert.CertificateFactory factory = java.security.cert.CertificateFactory.getInstance("X.509");
							for (java.security.cert.Certificate certificate : factory.generateCertificates(in)) {
								if (certificate instanceof java.security.cert.X509Certificate && certs.size() < 4) {
									java.security.cert.X509Certificate x509 = (java.security.cert.X509Certificate) certificate;
									Map<String, Object> cert = new LinkedHashMap<>();
									cert.put("subject", x509.getSubjectX500Principal().getName());
									cert.put("issuer", x509.getIssuerX500Principal().getName());
									cert.put("notBefore", x509.getNotBefore().toString());
									cert.put("notAfter", x509.getNotAfter().toString());
									cert.put("sigAlg", x509.getSigAlgName());
									certs.add(cert);
								}
							}
						} catch (Exception ignored) {
						}
					}
				}
			} catch (Exception ignored) {
			}
			if (!signFiles.isEmpty()) {
				schemes.add("V1");
			}
			schemes.addAll(detectBlockSchemes(task.input));
		}
		sign.put("apkSize", apkSize);
		sign.put("v1", !signFiles.isEmpty());
		sign.put("signFiles", signFiles);
		sign.put("schemes", schemes);
		sign.put("certs", certs);
		return sign;
	}

	/**
	 * 中文注释：解析 APK Signing Block 检测 v2/v3 签名方案。
	 * EOCD 定位中央目录偏移，其前为 [pair区大小][magic "APK Sig Block 42"]，
	 * 遍历 ID-Value：0x7109871a=V2，0xf05368c0=V3，0x1b93ad61=V3.1。
	 */
	private static List<String> detectBlockSchemes(Path apk) {
		List<String> schemes = new ArrayList<>();
		try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(apk.toFile(), "r")) {
			long length = raf.length();
			int tailLen = (int) Math.min(length, 22L + 65535L);
			byte[] tail = new byte[tailLen];
			raf.seek(length - tailLen);
			raf.readFully(tail);
			int eocd = -1;
			for (int i = tail.length - 22; i >= 0; i--) {
				if (tail[i] == 0x50 && tail[i + 1] == 0x4B && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
					eocd = i;
					break;
				}
			}
			if (eocd < 0 || eocd + 20 > tail.length) {
				return schemes;
			}
			long cdOffset = (tail[eocd + 16] & 0xFFL) | ((tail[eocd + 17] & 0xFFL) << 8) | ((tail[eocd + 18] & 0xFFL) << 16) | ((tail[eocd + 19] & 0xFFL) << 24);
			if (cdOffset < 32 || cdOffset > length) {
				return schemes;
			}
			byte[] magic = new byte[16];
			raf.seek(cdOffset - 16);
			raf.readFully(magic);
			if (!"APK Sig Block 42".equals(new String(magic, StandardCharsets.US_ASCII))) {
				return schemes;
			}
			byte[] sizeBytes = new byte[8];
			raf.seek(cdOffset - 24);
			raf.readFully(sizeBytes);
			long pairsLen = 0;
			for (int i = 7; i >= 0; i--) {
				pairsLen = (pairsLen << 8) | (sizeBytes[i] & 0xFFL);
			}
			long pos = cdOffset - 24 - pairsLen;
			long end = cdOffset - 24;
			while (pos + 12 <= end) {
				raf.seek(pos);
				byte[] head = new byte[12];
				raf.readFully(head);
				long pairLen = 0;
				for (int i = 7; i >= 0; i--) {
					pairLen = (pairLen << 8) | (head[i] & 0xFFL);
				}
				int id = (head[8] & 0xFF) | ((head[9] & 0xFF) << 8) | ((head[10] & 0xFF) << 16) | ((head[11] & 0xFF) << 24);
				if (id == 0x7109871A && !schemes.contains("V2")) {
					schemes.add("V2");
				} else if (id == 0xF05368C0 && !schemes.contains("V3")) {
					schemes.add("V3");
				} else if (id == 0x1B93AD61 && !schemes.contains("V3.1")) {
					schemes.add("V3.1");
				}
				pos += 8 + pairLen;
			}
		} catch (Exception ignored) {
		}
		return schemes;
	}

	/**
	 * 中文注释：资源接口——values 结构化（字符串/颜色）+ res 目录统计，供"资源"页签浏览。
	 */
	void handleRes(ApktoolWebServer.Task task, HttpExchange exchange) throws IOException {
		Path decoded = decodedDir(task);
		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, String>> strings = new ArrayList<>();
		List<Map<String, String>> colors = new ArrayList<>();
		Path valuesDir = decoded.resolve("res/values");
		if (Files.isDirectory(valuesDir)) {
			try (Stream<Path> stream = Files.list(valuesDir)) {
				for (Path file : (Iterable<Path>) stream.filter(f -> f.getFileName().toString().endsWith(".xml"))::iterator) {
					String text = Files.readString(file, StandardCharsets.UTF_8);
					Matcher sm = STRING_RES.matcher(text);
					while (sm.find()) {
						Map<String, String> item = new LinkedHashMap<>();
						item.put("n", sm.group(1));
						item.put("v", sm.group(2));
						strings.add(item);
					}
					Matcher cm = COLOR_RES.matcher(text);
					while (cm.find()) {
						Map<String, String> item = new LinkedHashMap<>();
						item.put("n", cm.group(1));
						item.put("v", cm.group(2));
						colors.add(item);
					}
				}
			}
		}
		Map<String, Integer> counts = new LinkedHashMap<>();
		Path resDir = decoded.resolve("res");
		int totalFiles = 0;
		if (Files.isDirectory(resDir)) {
			try (Stream<Path> stream = Files.walk(resDir)) {
				for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
					totalFiles++;
					String parent = file.getParent() == null ? "" : file.getParent().getFileName().toString().toLowerCase(Locale.ROOT);
					String bucket = parent.startsWith("values") ? "values"
							: parent.startsWith("drawable") ? "drawable"
							: parent.startsWith("mipmap") ? "mipmap"
							: parent.startsWith("layout") ? "layout"
							: parent.startsWith("anim") ? "anim"
							: parent.startsWith("raw") ? "raw"
							: "其他";
					counts.merge(bucket, 1, Integer::sum);
				}
			}
		}
		out.put("strings", strings);
		out.put("colors", colors);
		out.put("counts", counts);
		out.put("totalFiles", totalFiles);
		ApktoolWebServer.sendJson(exchange, 200, GSON.toJson(out));
	}

	/** 中文注释：入口 Activity = 带 MAIN + LAUNCHER 过滤器的 activity。 */
	private String findEntryActivity(String text) {
		Matcher blocks = Pattern.compile("<activity\\b[\\s\\S]*?</activity>").matcher(text);
		while (blocks.find()) {
			String block = blocks.group();
			if (block.contains("android.intent.action.MAIN") && block.contains("android.intent.category.LAUNCHER")) {
				return group(COMP_NAME, block);
			}
		}
		return null;
	}

	/** 中文注释：@string/xxx 形式的应用名回源到 values/strings.xml。 */
	private String resolveStringRes(Path decoded, String resName) {
		try {
			Path strings = decoded.resolve("res/values/strings.xml");
			if (!Files.isRegularFile(strings)) {
				return "@" + resName;
			}
			Matcher matcher = STRING_RES.matcher(Files.readString(strings, StandardCharsets.UTF_8));
			while (matcher.find()) {
				if (matcher.group(1).equals(resName)) {
					return matcher.group(2);
				}
			}
		} catch (IOException ignored) {
		}
		return "@" + resName;
	}

	private static String group(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static Integer num(String value) {
		try {
			return value == null ? null : Integer.valueOf(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Path decodedDir(ApktoolWebServer.Task task) {
		Path decoded = task.dir.resolve("decoded").normalize();
		return Files.isDirectory(decoded) ? decoded : task.dir;
	}
}
