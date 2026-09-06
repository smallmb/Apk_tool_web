package brut.apktool.web;

import com.google.gson.Gson;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 中文注释：adb 设备集成最小闭环——检测本机 adb、列出已连接设备、
 * 把修补后的签名 APK 一键安装到设备（MT 用户改完包验证的最后一步）。
 * adb 未安装或无设备时返回友好提示，不影响其他功能。
 */
final class AdbService {
	private static final Gson GSON = new Gson();

	/** 中文注释：按常见位置探测 adb 可执行文件（PATH / Android SDK 默认目录）。 */
	private static String resolveAdb() {
		String[] candidates = {
			"adb",
			Paths.get(System.getenv("LOCALAPPDATA") == null ? "" : System.getenv("LOCALAPPDATA"), "Android", "Sdk", "platform-tools", "adb.exe").toString(),
			"C:/Android/platform-tools/adb.exe",
			Paths.get(System.getProperty("user.home"), "AppData", "Local", "Android", "Sdk", "platform-tools", "adb.exe").toString(),
		};
		for (String candidate : candidates) {
			try {
				Process p = new ProcessBuilder(candidate, "version").redirectErrorStream(true).start();
				if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
					return candidate;
				}
			} catch (Exception ignored) {
			}
		}
		return null;
	}

	/** 中文注释：GET /api/tasks/{id}/adb —— adb 可用性与设备列表。 */
	void handleDevices(HttpExchange exchange) throws IOException {
		Map<String, Object> out = new LinkedHashMap<>();
		String adb = resolveAdb();
		out.put("adb", adb != null);
		List<Map<String, Object>> devices = new ArrayList<>();
		if (adb != null) {
			Process p = new ProcessBuilder(adb, "devices", "-l").redirectErrorStream(true).start();
			String output = readAll(p);
			for (String line : output.split("\n")) {
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("List of devices") || trimmed.startsWith("*")) {
					continue;
				}
				String[] parts = trimmed.split("\\s+");
				if (parts.length >= 2) {
					Map<String, Object> device = new LinkedHashMap<>();
					device.put("serial", parts[0]);
					device.put("state", parts[1]);
					for (String part : parts) {
						if (part.startsWith("model:")) {
							device.put("model", part.substring("model:".length()));
						}
					}
					devices.add(device);
				}
			}
		}
		out.put("devices", devices);
		ApktoolWebServer.sendJson(exchange, 200, GSON.toJson(out));
	}

	/** 中文注释：POST /api/tasks/{id}/adb/install —— 把 patched/patched-signed.apk 安装到第一个在线设备。 */
	void handleInstall(ApktoolWebServer.Task task, HttpExchange exchange) throws IOException {
		String adb = resolveAdb();
		if (adb == null) {
			ApktoolWebServer.sendJson(exchange, 400, "{\"error\":\"未找到 adb。请安装 Android 平台工具并加入 PATH 后重试。\"}");
			return;
		}
		Path apk = task.dir.resolve("patched/patched-signed.apk");
		if (!Files.isRegularFile(apk)) {
			apk = task.dir.resolve("patched/patched-unsigned.apk");
		}
		if (!Files.isRegularFile(apk)) {
			ApktoolWebServer.sendJson(exchange, 400, "{\"error\":\"还没有导出的 APK，请先在 DEX 工作台导出补丁 APK\"}");
			return;
		}
		Process list = new ProcessBuilder(adb, "devices").redirectErrorStream(true).start();
		String output = readAll(list);
		String serial = null;
		for (String line : output.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("List of devices") || trimmed.startsWith("*")) {
				continue;
			}
			String[] parts = trimmed.split("\\s+");
			if (parts.length >= 2 && "device".equals(parts[1])) {
				serial = parts[0];
				break;
			}
		}
		if (serial == null) {
			ApktoolWebServer.sendJson(exchange, 400, "{\"error\":\"没有在线设备。请连接手机并开启 USB 调试后刷新。\"}");
			return;
		}
		Process p = new ProcessBuilder(adb, "-s", serial, "install", "-r", apk.toAbsolutePath().toString()).redirectErrorStream(true).start();
		boolean finished;
		try {
			finished = p.waitFor(120, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			finished = false;
		}
		String result = finished ? readAll(p).trim() : "安装超时（120 秒）";
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("serial", serial);
		out.put("apk", apk.toAbsolutePath().toString());
		if (!finished) {
			p.destroyForcibly();
			out.put("ok", false);
			out.put("output", "安装超时（120 秒）");
		} else if (result.contains("Success")) {
			out.put("ok", true);
			out.put("output", result);
		} else {
			out.put("ok", false);
			out.put("output", result);
		}
		ApktoolWebServer.sendJson(exchange, 200, GSON.toJson(out));
	}

	private static String readAll(Process process) throws IOException {
		try (InputStream in = process.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
