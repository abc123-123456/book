import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 图书管理 · 后端（图书基础信息维护）—— 纯 JDK，无数据库、无第三方依赖。
 * 一个进程同时提供页面与接口：
 *   GET /  页面          GET /api/books?name=书名关键字   模糊查询列表
 *   GET /api/books/{no} 回显      POST /api/books  新增     PUT /api/books/{no} 修改
 */
public class BookBackend {

    static final int PORT = 8080;
    static final String API = "/api/books";
    static final Map<String, Book> DB = new ConcurrentSkipListMap<>();   // 内存数据，key = 图书编号

    /** 图书实体：record 不可变，修改即整体替换 */
    record Book(String bookNo, String bookName, String author, String publisher, int stock) { }

    public static void main(String[] args) throws IOException {
        DB.put("B001", new Book("B001", "Java 核心技术 卷I", "凯 S.霍斯特曼", "机械工业出版社", 12));
        DB.put("B002", new Book("B002", "深入理解 Java 虚拟机", "周志明", "机械工业出版社", 8));
        DB.put("B003", new Book("B003", "算法导论", "托马斯·科尔曼", "机械工业出版社", 5));
        DB.put("B004", new Book("B004", "数据库系统概念", "亚伯拉罕·西尔伯沙茨", "高等教育出版社", 20));
        DB.put("B005", new Book("B005", "计算机网络：自顶向下方法", "詹姆斯·库罗斯", "人民邮电出版社", 15));

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", BookBackend::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("页面 http://localhost:" + PORT + "/   接口 http://localhost:" + PORT + API);
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (!path.equals(API) && !path.startsWith(API + "/")) {
                page(ex, path);                                    // 非接口请求 → 返回前端页面
                return;
            }
            cors(ex);
            if ("OPTIONS".equals(method)) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            String no = path.length() > API.length() + 1
                    ? URLDecoder.decode(path.substring(API.length() + 1), StandardCharsets.UTF_8) : null;

            switch (method) {
                case "GET" -> {
                    if (no != null) {
                        Book b = DB.get(no);
                        if (b == null) fail(ex, 404, "图书编号 " + no + " 不存在");
                        else send(ex, 200, "查询成功", json(b));
                    } else {
                        String kw = param(ex.getRequestURI().getRawQuery()).toLowerCase();
                        List<Book> hit = DB.values().stream()
                                .filter(b -> b.bookName().toLowerCase().contains(kw)).toList();
                        send(ex, 200, "查询成功，共 " + hit.size() + " 条记录",
                                hit.stream().map(BookBackend::json).collect(Collectors.joining(",", "[", "]")));
                    }
                }
                case "POST" -> save(ex, null, body(ex));            // 新增：编号取表单值
                case "PUT" -> save(ex, no, body(ex));               // 修改：编号取路径值
                case "DELETE" -> {
                    Book b = no == null ? null : DB.remove(no);
                    if (b == null) fail(ex, 404, "图书编号 " + no + " 不存在，无法删除");
                    else send(ex, 200, "删除成功", json(b));
                }
                default -> fail(ex, 405, "不支持的请求方法：" + method);
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                fail(ex, 500, "服务器内部错误：" + e.getMessage());
            } catch (IOException ignore) {
                // 响应可能已输出
            }
        } finally {
            ex.close();
        }
    }

    // ==================== 业务与校验 ====================

    /** 新增 / 修改保存：no 为 null 表示新增 */
    static void save(HttpExchange ex, String no, Map<String, String> f) throws IOException {
        String key = no == null ? f.get("bookNo") : no;
        if (no != null && !DB.containsKey(no)) {
            fail(ex, 404, "图书编号 " + no + " 不存在，无法编辑");
            return;
        }
        String error = blank(key) ? "图书编号不能为空"
                : no == null && DB.containsKey(key) ? "图书编号 " + key + " 已存在，请勿重复新增"
                : check(f);
        if (error != null) {
            fail(ex, 400, error);
            return;
        }
        Book b = new Book(key, f.get("bookName"), f.get("author"), f.get("publisher"),
                Integer.parseInt(f.get("stock")));
        DB.put(key, b);
        send(ex, 200, no == null ? "新增成功" : "修改保存成功", json(b));
    }

    /** 关键字段校验：返回错误提示，通过返回 null */
    static String check(Map<String, String> f) {
        if (blank(f.get("bookName"))) return "书名不能为空";
        if (blank(f.get("author"))) return "作者不能为空";
        if (blank(f.get("publisher"))) return "出版社不能为空";
        String stock = f.get("stock");
        if (blank(stock)) return "库存不能为空";
        return stock.trim().matches("\\d{1,6}") ? null : "库存必须为 0 ~ 999999 的整数";
    }

    static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ==================== 请求解析 ====================

    /** 读取请求体，取出 5 个字段（前端固定以 JSON 提交） */
    static Map<String, String> body(HttpExchange ex) throws IOException {
        String json = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> f = new HashMap<>();
        for (String key : List.of("bookNo", "bookName", "author", "publisher", "stock")) {
            f.put(key, field(json, key));
        }
        return f;
    }

    /** 从 JSON 文本取字段值（字符串去引号并还原转义，数字原样），不引入 JSON 库 */
    static String field(String json, String key) {
        int i = json.indexOf('"' + key + '"');
        if (i < 0) return "";
        i = json.indexOf(':', i + key.length() + 2) + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return "";
        if (json.charAt(i) != '"') {                               // 数字（库存）
            int j = i;
            while (j < json.length() && ",}".indexOf(json.charAt(j)) < 0) j++;
            return json.substring(i, j).trim();
        }
        StringBuilder sb = new StringBuilder();
        for (i++; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') sb.append(json.charAt(++i));
            else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString();
    }

    /** 取查询串里的 name 参数 */
    static String param(String query) {
        if (query == null) return "";
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && "name".equals(pair.substring(0, i))) {
                return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8).trim();
            }
        }
        return "";
    }

    // ==================== 输出 ====================

    static String json(Book b) {
        return "{\"bookNo\":\"" + esc(b.bookNo()) + "\",\"bookName\":\"" + esc(b.bookName())
                + "\",\"author\":\"" + esc(b.author()) + "\",\"publisher\":\"" + esc(b.publisher())
                + "\",\"stock\":" + b.stock() + "}";
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static void write(HttpExchange ex, int code, String type, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    static void send(HttpExchange ex, int code, String msg, String data) throws IOException {
        write(ex, code, "application/json; charset=utf-8",
                "{\"code\":" + code + ",\"message\":\"" + esc(msg) + "\",\"data\":" + data + "}");
    }

    static void fail(HttpExchange ex, int code, String msg) throws IOException {
        send(ex, code, msg, "null");
    }

    /** 返回前端页面：每次读文件，改完 index.html 刷新即生效 */
    static void page(HttpExchange ex, String path) throws IOException {
        if ("/favicon.ico".equals(path)) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        Path file = Paths.get(System.getProperty("page", "index.html"));
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            write(ex, 404, "text/plain; charset=utf-8", "404 页面不存在：" + path);
        } else if (!Files.isRegularFile(file)) {
            write(ex, 500, "text/plain; charset=utf-8",
                    "未找到 " + file.toAbsolutePath() + "，请与 BookBackend 放在同一目录运行");
        } else {
            write(ex, 200, "text/html; charset=utf-8", Files.readString(file, StandardCharsets.UTF_8));
        }
    }

    static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }
}
