import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 图书分类管理后端：纯 JDK、无数据库，内存模拟存储，同时托管前端页面（一个服务一个端口）。 */
public class CategoryBackend {
    static final int PORT = 8090;
    static final String API = "/api/categories";
    static final Path PAGE = Path.of(System.getProperty("page", "index.html"));
    /** 分类表：分类编号 -> [编号, 名称, 描述]，按编号升序 */
    static final Map<String, String[]> DB = new ConcurrentSkipListMap<>();
    /** 图书表：分类编号 -> 引用该分类的图书（用于"被引用不可删除"校验） */
    static final Map<String, List<String>> BOOKS = new ConcurrentSkipListMap<>();

    public static void main(String[] args) throws IOException {
        // 演示数据：C001、C002 已被图书引用（不可删除），C003 未被引用（可删除）
        DB.put("C001", new String[]{"C001", "计算机", "程序设计、算法等技术图书"});
        DB.put("C002", new String[]{"C002", "文学", "小说、散文、诗歌"});
        DB.put("C003", new String[]{"C003", "历史", "中外历史、人物传记"});
        BOOKS.put("C001", new CopyOnWriteArrayList<>(List.of("B001《Java 核心技术》", "B002《算法导论》")));
        BOOKS.put("C002", new CopyOnWriteArrayList<>(List.of("B003《活着》")));

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                if (!path.startsWith(API)) { page(ex, path); return; }
                String rest = URLDecoder.decode(path.substring(API.length()), StandardCharsets.UTF_8);
                while (rest.startsWith("/")) rest = rest.substring(1);
                String[] parts = rest.isEmpty() ? new String[0] : rest.split("/");
                String no = parts.length > 0 ? parts[0] : null;
                switch (ex.getRequestMethod()) {
                    case "GET" -> { if (no == null) list(ex); else fail(ex, 404, "接口不存在：" + path); }
                    case "POST" -> { if (no == null) create(ex); else addBook(ex, no); }
                    // 新增：修改分类（分类编号为主键，只能通过路径定位，不能改名）
                    case "PUT" -> { if (no == null) fail(ex, 400, "缺少分类编号"); else update(ex, no); }
                    case "DELETE" -> {
                        if (no == null) fail(ex, 400, "缺少分类编号");
                        else if (parts.length > 1) unbindBook(ex, no);
                        else remove(ex, no);
                    }
                    default -> fail(ex, 405, "不支持的请求方法：" + ex.getRequestMethod());
                }
            } catch (Exception e) {
                fail(ex, 500, "服务器内部错误：" + e.getMessage());
            } finally {
                ex.close();
            }
        });
        server.start();
        System.out.println("页面 http://localhost:" + PORT + "/    接口 " + API);
    }

    // ==================== 业务处理 ====================

    /** 分类列表：附带引用图书数与书名，供页面展示及判断能否删除 */
    static void list(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        DB.values().forEach(c -> sb.append(sb.length() > 1 ? "," : "").append(json(c)));
        send(ex, 200, "查询成功，共 " + DB.size() + " 个分类", sb.append("]").toString());
    }

    /** 新增分类：必填、长度、编号唯一、名称唯一校验 */
    static void create(HttpExchange ex) throws IOException {
        Map<String, String> f = fields(ex);
        String no = f.get("categoryNo").trim(), name = f.get("categoryName").trim(), remark = f.get("remark").trim();
        String err = no.isEmpty() ? "分类编号不能为空"
                : name.isEmpty() ? "分类名称不能为空"
                : no.length() > 20 ? "分类编号不能超过 20 个字符"
                : name.length() > 30 ? "分类名称不能超过 30 个字符"
                : remark.length() > 100 ? "分类描述不能超过 100 个字符"
                : DB.containsKey(no) ? "分类编号 " + no + " 已存在"
                : DB.values().stream().anyMatch(c -> c[1].equals(name)) ? "分类名称“" + name + "”已存在" : null;
        if (err != null) { fail(ex, 400, err); return; }
        DB.put(no, new String[]{no, name, remark});
        send(ex, 200, "新增成功：" + no + " " + name, json(DB.get(no)));
    }

    /** 修改分类：按编号定位，编号不可变，只更新名称与描述；名称需与其它分类不重复 */
    static void update(HttpExchange ex, String no) throws IOException {
        String[] c = DB.get(no);
        if (c == null) { fail(ex, 404, "分类 " + no + " 不存在"); return; }
        Map<String, String> f = fields(ex);
        String name = f.get("categoryName").trim(), remark = f.get("remark").trim();
        String err = name.isEmpty() ? "分类名称不能为空"
                : name.length() > 30 ? "分类名称不能超过 30 个字符"
                : remark.length() > 100 ? "分类描述不能超过 100 个字符"
                : DB.entrySet().stream().anyMatch(e -> !e.getKey().equals(no) && e.getValue()[1].equals(name))
                ? "分类名称“" + name + "”已存在" : null;
        if (err != null) { fail(ex, 400, err); return; }
        if (name.equals(c[1]) && remark.equals(c[2])) {
            send(ex, 200, "内容未变化，无需保存：" + no + " " + name, json(c));
            return;
        }
        // 原地替换数组内容：编号不变，BOOKS 中已有的图书引用自动保留
        DB.put(no, new String[]{no, name, remark});
        send(ex, 200, "修改成功：" + no + " " + name, json(DB.get(no)));
    }

    /** 删除分类：仅未被任何图书引用的分类可删除 */
    static void remove(HttpExchange ex, String no) throws IOException {
        String[] c = DB.get(no);
        if (c == null) { fail(ex, 404, "分类 " + no + " 不存在"); return; }
        List<String> books = BOOKS.getOrDefault(no, List.of());
        if (!books.isEmpty()) {
            fail(ex, 400, "分类 " + no + "（" + c[1] + "）已被 " + books.size() + " 本图书引用（"
                    + String.join("、", books) + "），不能删除");
            return;
        }
        DB.remove(no);
        send(ex, 200, "删除成功：" + no + " " + c[1], json(c));
    }

    /** 为分类挂入一本图书（模拟图书引用，便于演示"解除引用后即可删除"） */
    static void addBook(HttpExchange ex, String no) throws IOException {
        if (!DB.containsKey(no)) { fail(ex, 404, "分类 " + no + " 不存在"); return; }
        String book = field(readBody(ex), "bookName").trim();
        if (book.isEmpty()) { fail(ex, 400, "图书名称不能为空"); return; }
        List<String> books = BOOKS.computeIfAbsent(no, k -> new CopyOnWriteArrayList<>());
        books.add(book);
        send(ex, 200, "已挂入《" + book + "》到分类 " + no + "，当前引用 " + books.size() + " 本", json(DB.get(no)));
    }

    /** 解除该分类最后一本图书的引用（引用归零后即可删除） */
    static void unbindBook(HttpExchange ex, String no) throws IOException {
        if (!DB.containsKey(no)) { fail(ex, 404, "分类 " + no + " 不存在"); return; }
        List<String> books = BOOKS.get(no);
        if (books == null || books.isEmpty()) { fail(ex, 400, "分类 " + no + " 下没有可解除的图书"); return; }
        send(ex, 200, "已解除《" + books.remove(books.size() - 1) + "》对分类 " + no + " 的引用", json(DB.get(no)));
    }

    // ==================== 工具方法 ====================

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 无 JSON 库，按 key 提取前端提交的 JSON 字符串字段 */
    static Map<String, String> fields(HttpExchange ex) throws IOException {
        String json = readBody(ex);
        Map<String, String> m = new HashMap<>();
        List.of("categoryNo", "categoryName", "remark").forEach(k -> m.put(k, field(json, k)));
        return m;
    }

    static String field(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        i = json.indexOf(':', i);
        if (i < 0) return "";
        while (i < json.length() && json.charAt(i) != '"') i++;
        StringBuilder sb = new StringBuilder();
        for (i++; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) sb.append(json.charAt(++i));
            else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString();
    }

    static String json(String[] c) {
        List<String> books = BOOKS.getOrDefault(c[0], List.of());
        return "{\"categoryNo\":\"" + esc(c[0]) + "\",\"categoryName\":\"" + esc(c[1]) + "\",\"remark\":\"" + esc(c[2])
                + "\",\"bookCount\":" + books.size() + ",\"books\":\"" + esc(String.join("、", books)) + "\"}";
    }

    static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }

    static void send(HttpExchange ex, int code, String msg, String data) throws IOException {
        byte[] b = ("{\"code\":" + code + ",\"message\":\"" + esc(msg) + "\",\"data\":" + data + "}")
                .getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }

    static void fail(HttpExchange ex, int code, String msg) throws IOException { send(ex, code, msg, "null"); }

    /** 非接口请求统一返回前端页面（每次重读文件，改完页面刷新即生效） */
    static void page(HttpExchange ex, String path) throws IOException {
        if ("/favicon.ico".equals(path)) { ex.sendResponseHeaders(204, -1); return; }
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            byte[] b = ("404：" + path).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, b.length);
            ex.getResponseBody().write(b);
            return;
        }
        String html;
        try { html = Files.readString(PAGE, StandardCharsets.UTF_8); }
        catch (IOException e) {
            html = "<meta charset=\"utf-8\"><h1>未找到页面文件 " + PAGE.toAbsolutePath() + "</h1>"
                    + "<p>请在 index.html 所在目录启动，或用 -Dpage=绝对路径 指定。</p>";
        }
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
    }
}