import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * My Hero Craft の英語 Wiki を、MOD のソースから作る。
 * 使い方: java WikiGen.java <MODのフォルダ> <出力フォルダ> <公開URL>
 * 例:     java WikiGen.java "D:/mods/1.20.1 - mha" ../docs https://USERNAME.github.io/my-hero-craft-wiki
 */
public class WikiGen {
    static Path mod, out;
    static String base;
    static Map<String, String> en = new HashMap<>();
    static final String SITE = "My Hero Craft Wiki";

    public static void main(String[] a) throws Exception {
        mod = Path.of(a[0]);
        out = Path.of(a[1]);
        base = a.length > 2 ? a[2].replaceAll("/+$", "") : "https://kujira-90.github.io/my-hero-craft-wiki";
        en = loadLang(mod.resolve("src/main/resources/assets/mhc/lang/en_us.json"));
        Path java = mod.resolve("src/main/java/com/kujira/mhc");

        Map<String, List<String>> cats = categories(strip(read(java.resolve("Math/SkillMath.java"))));
        Map<String, Tree> trees = parseTrees(strip(read(java.resolve("gui/SkillTree/SkillTreeInit.java"))));
        // PassiveList に入っている技と、名前に [Passive] が付いた技はパッシブ
        Set<String> passiveList = new HashSet<>();
        Matcher pm = Pattern.compile("numList\\.add\\(\"([a-z0-9_]+)\"\\)").matcher(method(strip(read(java.resolve("Math/SkillMath.java"))), "List<String> PassiveList\\(\\)\\s*\\{"));
        while (pm.find()) passiveList.add(pm.group(1));
        for (Tree t : trees.values())
            for (Node n : t.nodes)
                if (passiveList.contains(n.id) || skillName(n.id).contains("[Passive]")) n.passive = true;
        Cd cd = new Cd(strip(read(java.resolve("Math/SkillCD.java"))));
        Set<String> dispatched = dispatched(strip(read(java.resolve("Ability/KoseiSkills.java"))));
        List<Npc> npcs = npcs(java);

        Files.createDirectories(out.resolve("quirks"));
        List<String> urls = new ArrayList<>();
        List<String[]> quirkRows = new ArrayList<>(); // id, name, category, users, desc
        for (var c : cats.entrySet()) {
            for (String q : c.getValue()) {
                Tree t = trees.get(q);
                if (t == null) continue;
                Kosei k = kosei(q);
                quirkRows.add(new String[]{q, k.name, c.getKey(), k.users, plain(lang("mhc.chat.message.note." + q + "_desc"))});
                write("quirks/" + q + ".html", quirkPage(q, k, c.getKey(), t, cd, dispatched, npcs));
                urls.add("quirks/" + q + ".html");
            }
        }
        write("quirks/index.html", quirkIndex(quirkRows));
        urls.add("quirks/");
        write("characters.html", charactersPage(npcs));
        urls.add("characters.html");
        Path res = mod.resolve("src/main/resources/assets/mhc");
        for (Npc n : npcs) {
            write("characters/" + n.id() + ".html", charPage(n, trees));
            urls.add("characters/" + n.id() + ".html");
            for (Part p : n.parts()) {
                for (String f : List.of(p.geo(), p.tex())) {
                    Path src = res.resolve(f), dst = out.resolve("assets").resolve(f);
                    if (Files.exists(src)) {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        write("viewer.js", VIEWER);
        write("guide.html", guidePage());
        urls.add("guide.html");
        write("index.html", homePage(quirkRows.size(), npcs.size()));
        urls.add("");
        write("404.html", layout("Page not found", "The page you were looking for does not exist.", "",
                "<h1>Page not found</h1><p>Go back to the <a href=\"" + rel(0) + "index.html\">home page</a>.</p>", 0));
        write("style.css", CSS);
        write("search.js", JS);
        write(".nojekyll", "");
        write("robots.txt", "User-agent: *\nAllow: /\nSitemap: " + base + "/sitemap.xml\n");
        StringBuilder sm = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (String u : urls) sm.append("  <url><loc>").append(base).append("/").append(u).append("</loc></url>\n");
        write("sitemap.xml", sm.append("</urlset>\n").toString());
        System.out.println("quirks=" + quirkRows.size() + " characters=" + npcs.size() + " pages=" + (urls.size() + 1));
    }

    // ------------------------------------------------------------------ 読み込み

    static String read(Path p) throws Exception { return Files.readString(p, StandardCharsets.UTF_8); }

    static void write(String rel, String s) throws Exception {
        Path p = out.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }

    static Map<String, String> loadLang(Path p) throws Exception {
        Map<String, String> m = new HashMap<>();
        Matcher r = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(read(p));
        while (r.find()) m.put(r.group(1), r.group(2).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\"));
        return m;
    }

    static String lang(String k) { return en.getOrDefault(k, ""); }

    /** コメントを消す(文字列の中はそのまま)。 */
    static String strip(String s) {
        StringBuilder b = new StringBuilder();
        int i = 0, n = s.length();
        boolean str = false;
        char q = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (str) {
                b.append(c);
                if (c == '\\' && i + 1 < n) { b.append(s.charAt(i + 1)); i += 2; continue; }
                if (c == q) str = false;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') { str = true; q = c; b.append(c); i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') { while (i < n && s.charAt(i) != '\n') i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') { int e = s.indexOf("*/", i + 2); i = e < 0 ? n : e + 2; continue; }
            b.append(c);
            i++;
        }
        return b.toString();
    }

    static String body(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open + 1, i);
        }
        return src.substring(open + 1);
    }

    static String method(String src, String header) {
        Matcher m = Pattern.compile(header).matcher(src);
        if (!m.find()) return "";
        return body(src, src.indexOf('{', m.end() - 1));
    }

    static Map<String, List<String>> categories(String skillMath) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        String[][] lists = {{"StudentList", "U.A. Students"}, {"HeroList", "Heroes"}, {"VigilanteList", "Vigilantes"},
                {"VillainList", "Villains"}, {"OtherList", "Other Quirks"}};
        for (String[] l : lists) {
            List<String> q = new ArrayList<>();
            Matcher r = Pattern.compile("numList\\.add\\(\"([a-z0-9_]+)\"\\)").matcher(method(skillMath, "List<String> " + l[0] + "\\(\\)\\s*\\{"));
            while (r.find()) q.add(r.group(1));
            m.put(l[1], q);
        }
        return m;
    }

    static Set<String> dispatched(String ks) {
        Set<String> s = new HashSet<>();
        Matcher m = Pattern.compile("skill\\.(?:equals|startsWith|contains)\\(\\s*\"([a-z0-9_]+)\"").matcher(ks);
        while (m.find()) s.add(m.group(1));
        return s;
    }

    // ------------------------------------------------------------------ 文字

    record Kosei(String name, String users) {}

    static Kosei kosei(String q) {
        String raw = noFormat(lang("mhc.chat.message.kosei." + q));
        if (raw.isEmpty()) return new Kosei(titleCase(q), "");
        Matcher m = Pattern.compile("^(.*?)\\s*[\\[(](?:Users?|Main users?)\\s*:\\s*(.*)[\\])]\\s*$", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (m.find()) return new Kosei(m.group(1).trim(), m.group(2).trim());
        int b = raw.indexOf('[');
        return b > 0 ? new Kosei(raw.substring(0, b).trim(), raw.substring(b + 1).replaceAll("]$", "").trim()) : new Kosei(raw.trim(), "");
    }

    static String titleCase(String id) {
        return Arrays.stream(id.split("_")).map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(Collectors.joining(" "));
    }

    static String noFormat(String s) { return s.replaceAll("§.", ""); }

    /** "[タイトル](説明)" を {タイトル, 説明} に分ける。形が違えば null。 */
    static String[] splitNote(String s) {
        s = noFormat(s).trim();
        if (!s.startsWith("[")) return null;
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') d++;
            else if (c == ']' && --d == 0) {
                String rest = s.substring(i + 1).trim();
                if (rest.startsWith("(") && rest.endsWith(")")) return new String[]{s.substring(1, i), rest.substring(1, rest.length() - 1)};
                return new String[]{s.substring(1, i), rest};
            }
        }
        return null;
    }

    static final Pattern ATK = Pattern.compile("<ATK:\\s*([0-9.]+)\\s*(?:\\{([^}]*)\\})?\\s*>");

    static String atk(String desc) {
        Matcher m = ATK.matcher(desc);
        if (!m.find()) return "";
        String s = m.group(1);
        if (m.group(2) != null && !m.group(2).isBlank()) {
            String parts = Arrays.stream(m.group(2).split(",")).map(String::trim).map(p -> {
                String[] kv = p.split(":");
                if (kv.length != 2) return p;
                String k = kv[0].trim();
                k = k.equals("STR") ? "Strength" : k.equals("QUIRK") ? "Quirk" : k.equals("ND") ? "Needs" : titleCase(k.toLowerCase());
                return k + " " + kv[1].trim();
            }).collect(Collectors.joining(", "));
            s += " (" + parts + ")";
        }
        return s;
    }

    static String plain(String s) {
        String[] p = splitNote(s);
        String d = p != null ? p[1] : noFormat(s);
        return ATK.matcher(d).replaceAll("").replaceAll("<([^<>]*)>", "$1").replaceAll("\\s+", " ").trim();
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------ スキルツリー

    static final class Node {
        String id, frame = "";
        int cost;
        boolean passive;
        List<Node> pre = new ArrayList<>(), children = new ArrayList<>();
    }

    static final class Tree {
        String quirk;
        Node root;
        List<Node> nodes = new ArrayList<>();
    }

    static Map<String, Tree> parseTrees(String src) {
        Map<String, Tree> trees = new HashMap<>();
        String all = method(src, "static void registerAll\\(\\)\\s*\\{");
        for (String st : all.split(";")) {
            st = st.trim();
            if (st.isEmpty()) continue;
            Matcher m;
            Tree t = null;
            if ((m = Pattern.compile("^registerOneSkills\\(\"([a-z0-9_]+)\"\\)$").matcher(st)).find()) {
                t = simple(m.group(1), 0, new String[]{m.group(1)}, new int[]{0});
            } else if ((m = Pattern.compile("^registerPassiveOnly\\(\"([a-z0-9_]+)\"\\s*,\\s*(\\d+)\\)$").matcher(st)).find()) {
                t = simple(m.group(1), Integer.parseInt(m.group(2)), new String[0], new int[0]);
            } else if ((m = Pattern.compile("^registerSimple\\(\"([a-z0-9_]+)\"\\s*,\\s*(\\d+)\\s*(?:,\\s*new int\\[\\]\\s*\\{([^}]*)\\})?((?:\\s*,\\s*\"[a-z0-9_]+\")*)\\s*\\)$", Pattern.DOTALL).matcher(st)).find()) {
                List<String> names = new ArrayList<>();
                Matcher n = Pattern.compile("\"([a-z0-9_]+)\"").matcher(m.group(4));
                while (n.find()) names.add(n.group(1));
                int[] costs = new int[names.size()];
                if (m.group(3) != null) {
                    String[] cs = m.group(3).split(",");
                    for (int i = 0; i < costs.length && i < cs.length; i++) costs[i] = num(cs[i]);
                }
                t = simple(m.group(1), Integer.parseInt(m.group(2)), names.toArray(new String[0]), costs);
            } else if ((m = Pattern.compile("^(register[A-Z][A-Za-z0-9]*)\\(\\)$").matcher(st)).find()) {
                t = manual(method(src, "private static void " + m.group(1) + "\\(\\)\\s*\\{"));
            }
            if (t != null && t.quirk != null) trees.put(t.quirk, t);
        }
        return trees;
    }

    static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    static Tree simple(String q, int passive, String[] skills, int[] costs) {
        Tree t = new Tree();
        t.quirk = q;
        t.root = new Node();
        t.root.id = q;
        Node prev = t.root;
        for (int i = 1; i <= passive; i++) prev = add(t, q + "_passive" + i, 0, true, prev);
        prev = t.root;
        for (int i = 0; i < skills.length; i++) prev = add(t, q + "_" + skills[i], costs[i], false, prev);
        return t;
    }

    static Node add(Tree t, String id, int cost, boolean passive, Node pre) {
        Node n = new Node();
        n.id = id;
        n.cost = cost;
        n.passive = passive || id.contains("_passive");
        if (pre != null) { n.pre.add(pre); pre.children.add(n); }
        t.nodes.add(n);
        return n;
    }

    /** 手で書いたツリー(1つのメソッド)を読む。 */
    static Tree manual(String b) {
        Tree t = new Tree();
        Matcher m = Pattern.compile("String\\s+quirk\\s*=\\s*\"([a-z0-9_]+)\"").matcher(b);
        String quirk = m.find() ? m.group(1) : null;
        m = Pattern.compile("SkillTreeRegistry\\.register\\(\\s*(?:\"([a-z0-9_]+)\"|quirk)").matcher(b);
        if (m.find() && m.group(1) != null) quirk = m.group(1);
        if (quirk == null) return t;
        t.quirk = quirk;
        t.root = new Node();
        t.root.id = quirk;
        Map<String, Node> vars = new HashMap<>();
        m = Pattern.compile("SkillNode\\s+(\\w+)\\s*=\\s*new SkillNode\\(").matcher(b);
        while (m.find()) vars.put(m.group(1), t.root);
        // 1つずつ書いたパッシブのループ
        m = Pattern.compile("int\\s+passive\\s*=\\s*(\\d+)").matcher(b);
        int passive = m.find() ? Integer.parseInt(m.group(1)) : 0;
        boolean loop = b.contains("\"_passive\" + i") || b.contains("\"_passive\"+i");
        Node lastPassive = t.root;
        if (loop) for (int i = 1; i <= passive; i++) lastPassive = add(t, quirk + "_passive" + i, 0, true, lastPassive);
        if (loop) vars.put("prev", lastPassive);
        m = Pattern.compile("SkillNode\\s+(\\w+)\\s*=\\s*SkillNode\\.ofQuarkKey\\(([^;]*)\\);").matcher(b);
        while (m.find()) {
            String var = m.group(1);
            List<String> args = args(m.group(2));
            String id = evalId(args.get(0), quirk);
            if (id == null) continue; // ループの中の _passive + i
            Node n = new Node();
            n.id = id;
            n.cost = args.size() >= 5 ? num(args.get(4)) : 0;
            n.passive = id.contains("_passive");
            t.nodes.add(n);
            vars.put(var, n);
        }
        m = Pattern.compile("(\\w+)\\.setPassive\\(true\\)").matcher(b);
        while (m.find()) if (vars.containsKey(m.group(1))) vars.get(m.group(1)).passive = true;
        m = Pattern.compile("(\\w+)\\.setFrameType\\(SkillNode\\.FrameType\\.(\\w+)\\)").matcher(b);
        while (m.find()) if (vars.containsKey(m.group(1))) vars.get(m.group(1)).frame = m.group(2);
        m = Pattern.compile("(\\w+)\\.addPrerequisite\\((\\w+)\\)").matcher(b);
        while (m.find()) {
            Node c = vars.get(m.group(1)), p = vars.get(m.group(2));
            if (c == null || p == null || c.pre.contains(p)) continue;
            c.pre.add(p);
            p.children.add(c);
        }
        return t;
    }

    static List<String> args(String s) {
        List<String> r = new ArrayList<>();
        int d = 0, st = 0;
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') str = !str;
            if (str) continue;
            if (c == '(' || c == '{' || c == '[') d++;
            else if (c == ')' || c == '}' || c == ']') d--;
            else if (c == ',' && d == 0) { r.add(s.substring(st, i).trim()); st = i + 1; }
        }
        r.add(s.substring(st).trim());
        return r;
    }

    static String evalId(String expr, String quirk) {
        if (expr.contains(" i") || expr.endsWith("i")) return null;
        StringBuilder b = new StringBuilder();
        for (String part : expr.split("\\+")) {
            part = part.trim();
            if (part.startsWith("\"") && part.endsWith("\"")) b.append(part, 1, part.length() - 1);
            else if (part.equals("quirk")) b.append(quirk);
            else return null;
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ クールダウン(SkillCD を読む。式が複雑なものは "varies")

    static final class Cd {
        Map<String, String> cases = new HashMap<>();
        Map<String, String> quirkDefault = new HashMap<>();
        Map<String, Map<String, String>> quirkSub = new HashMap<>();
        Set<String> toggleQuirk = Set.of("hypertrophy", "rivet", "heavypayload", "barrier");

        Cd(String src) {
            String fn = method(src, "public static int CD\\(LivingEntity player,\\s*String skill\\)\\s*\\{");
            int sw = fn.indexOf("switch (fullSkill)");
            String sb = body(fn, fn.indexOf('{', sw));
            Matcher m = Pattern.compile("case\\s+((?:\"[a-z0-9_]+\"\\s*,?\\s*)+)->\\s*(?:cd\\s*=\\s*)?([^;]+);", Pattern.DOTALL).matcher(sb);
            while (m.find()) {
                Matcher n = Pattern.compile("\"([a-z0-9_]+)\"").matcher(m.group(1));
                String v = m.group(2).replaceAll("\\s+", " ").trim();
                while (n.find()) cases.put(n.group(1), v);
            }
            String head = fn.substring(0, sw);
            // if (skill.equals("q")) cd = N;  /  cd = Func(player,now);  /  { cd=N; if (now.equals("s")) cd = M; ... }
            Matcher q = Pattern.compile("if \\(skill\\.equals\\(\"([a-z0-9_]+)\"\\)\\)\\s*(\\{)?").matcher(head);
            while (q.find()) {
                String quirk = q.group(1);
                String block;
                if (q.group(2) != null) block = body(head, q.end() - 1);
                else { int e = head.indexOf(';', q.end()); block = head.substring(q.end(), e + 1); }
                Matcher f = Pattern.compile("cd\\s*=\\s*([A-Z][A-Za-z]*)\\(player,\\s*now\\)").matcher(block);
                if (f.find()) {
                    String fb = method(src, "static float " + f.group(1) + "\\(LivingEntity player,\\s*String skill\\)\\s*\\{");
                    Matcher d = Pattern.compile("float cd\\s*=\\s*([0-9.]+)f?").matcher(fb);
                    if (d.find()) quirkDefault.put(quirk, d.group(1));
                    Matcher s = Pattern.compile("skill\\.equals\\(\"([a-z0-9_]+)\"\\)\\)\\s*\\{?\\s*cd\\s*=\\s*([^;]+);").matcher(fb);
                    while (s.find()) quirkSub.computeIfAbsent(quirk, k -> new HashMap<>()).put(s.group(1), s.group(2).trim());
                    continue;
                }
                Matcher d = Pattern.compile("^\\s*cd\\s*=\\s*([0-9.]+)f?\\s*;").matcher(block);
                if (d.find()) quirkDefault.put(quirk, d.group(1)); // 後の同じ個性のブロックが上書きする(SkillCD と同じ順)
                Matcher s = Pattern.compile("now\\.equals\\(\"([a-z0-9_]+)\"\\)\\)\\s*\\{?\\s*(?:if[^;]*\\)\\s*)?cd\\s*=\\s*([^;]+);").matcher(block);
                while (s.find()) quirkSub.computeIfAbsent(quirk, k -> new HashMap<>()).put(s.group(1), s.group(2).trim());
            }
        }

        /** 秒(文字)。分からなければ "" 。 */
        String of(String skill) {
            if (cases.containsKey(skill)) return seconds(cases.get(skill));
            int i = skill.indexOf('_');
            String q = i < 0 ? skill : skill.substring(0, i), now = i < 0 ? "" : skill.substring(i + 1);
            if (toggleQuirk.contains(q)) return "On toggle-off";
            Map<String, String> sub = quirkSub.get(q);
            if (sub != null && sub.containsKey(now)) return seconds(sub.get(now));
            if (quirkDefault.containsKey(q)) return seconds(quirkDefault.get(q));
            return "None";
        }

        static String seconds(String v) {
            v = v.replaceAll("f$", "").trim();
            if (v.matches("[0-9.]+")) {
                double d = Double.parseDouble(v);
                if (d == 0) return "None";
                return d == Math.floor(d) ? String.valueOf((int) d) : String.valueOf(d);
            }
            Matcher m = Pattern.compile("\\?\\s*([0-9.]+)f?\\s*:\\s*0").matcher(v);
            if (m.find()) return seconds(m.group(1)) + " (when turned off)";
            m = Pattern.compile("\\?\\s*0\\s*:\\s*([0-9.]+)f?").matcher(v);
            if (m.find()) return seconds(m.group(1));
            return "varies";
        }
    }

    // ------------------------------------------------------------------ NPC

    /** 3Dで描く部品: モデル(geo)・テクスチャ・隠す骨(防具で着ていない部位)。 */
    record Part(String geo, String tex, List<String> hide) {}

    record Npc(String id, String name, String group, int[] stats, List<String> quirks, int level, List<Part> parts) {
        Npc(String id, String name, String group, int[] stats, List<String> quirks, int level) { this(id, name, group, stats, quirks, level, new ArrayList<>()); }
    }

    static Map<String, String> parentOf = new HashMap<>();
    static Map<String, Path> classFile = new HashMap<>();

    static List<String> ancestors(String c) {
        List<String> l = new ArrayList<>();
        for (int i = 0; c != null && i < 20; i++, c = parentOf.get(c)) l.add(c);
        return l;
    }

    /** if (object instanceof A || object instanceof B) return rl("x") の並びを読む。最後の要素は {"", 既定のパス}。 */
    static List<String[]> chain(String body) {
        List<String[]> r = new ArrayList<>();
        Matcher m = Pattern.compile("if\\s*\\(([^{};]*?)\\)\\s*\\{?\\s*return\\s+(?:rl\\(|new ResourceLocation\\(\\s*MyHeroCraftMod\\.ID\\s*,\\s*)\"([^\"]+)\"").matcher(body);
        while (m.find()) {
            List<String> cs = new ArrayList<>();
            Matcher c = Pattern.compile("instanceof\\s+(\\w+)").matcher(m.group(1));
            while (c.find()) cs.add(c.group(1));
            if (!cs.isEmpty()) r.add(new String[]{String.join(",", cs), m.group(2)});
        }
        Matcher d = Pattern.compile("return\\s+(?:rl\\(|new ResourceLocation\\(\\s*MyHeroCraftMod\\.ID\\s*,\\s*)\"([^\"]+)\"\\s*\\)\\s*;\\s*$").matcher(body.trim());
        if (d.find()) r.add(new String[]{"", d.group(1)});
        return r;
    }

    static String resolve(List<String[]> chain, String cls) {
        List<String> anc = ancestors(cls);
        for (String[] e : chain) {
            if (e[0].isEmpty()) return e[1];
            for (String c : e[0].split(",")) if (anc.contains(c)) return e[1];
        }
        return null;
    }

    static Map<String, List<String[]>[]> modelChains = new HashMap<>();

    /** コスチュームの item クラス → 使っている Renderer → Model を読んで、{geo, texture} を返す。分からなければ null。 */
    @SuppressWarnings("unchecked")
    static String[] armorModel(String itemCls) throws Exception {
        for (String c : ancestors(itemCls)) {
            Path f = classFile.get(c);
            if (f == null) return null;
            Matcher r = Pattern.compile("new\\s+(\\w*Renderer)\\s*\\(").matcher(strip(read(f)));
            if (!r.find() || !classFile.containsKey(r.group(1))) continue;
            Matcher mm = Pattern.compile("super\\(\\s*new\\s+(\\w+)\\s*(?:<[^>]*>)?\\s*\\(").matcher(strip(read(classFile.get(r.group(1)))));
            if (!mm.find() || !classFile.containsKey(mm.group(1))) return null;
            List<String[]>[] ch = modelChains.get(mm.group(1));
            if (ch == null) {
                String ms = strip(read(classFile.get(mm.group(1))));
                ch = new List[]{chain(method(ms, "public ResourceLocation getModelResource\\(\\w+ \\w+\\)\\s*\\{")),
                        chain(method(ms, "public ResourceLocation getTextureResource\\(\\w+ \\w+\\)\\s*\\{"))};
                modelChains.put(mm.group(1), ch);
            }
            String g = resolve(ch[0], itemCls), t = resolve(ch[1], itemCls);
            return g != null && t != null ? new String[]{g, t} : null;
        }
        return null;
    }

    static List<Npc> npcs(Path java) throws Exception {
        String reg = strip(read(java.resolve("entity/ModEntityType.java")));
        Map<String, String> classToId = new HashMap<>();
        Matcher m = Pattern.compile("register\\(\"([a-z0-9_]+)\",\\s*EntityType\\.Builder\\.(?:<\\w+>)?of\\((\\w+)::new").matcher(reg);
        while (m.find()) classToId.putIfAbsent(m.group(2), m.group(1));
        try (Stream<Path> w = Files.walk(java)) {
            for (Path f : w.filter(p -> p.toString().endsWith(".java")).toList()) {
                String cls = f.getFileName().toString().replace(".java", "");
                classFile.put(cls, f);
                Matcher e = Pattern.compile("class\\s+" + cls + "(?:<[^>{]*>)?\\s+extends\\s+(\\w+)").matcher(read(f));
                if (e.find()) parentOf.put(cls, e.group(1));
            }
        }
        // 体(NPCのモデル)と、着ているコスチュームのモデル・テクスチャ
        String hero = strip(read(java.resolve("entity/Hero/Pro/client/Pro_HeroModel.java")));
        List<String[]> bodyGeo = chain(method(hero, "public ResourceLocation getModelResource\\(T object\\)\\s*\\{"));
        List<String[]> bodyTex = chain(method(hero, "private ResourceLocation textureResource\\(T object\\)\\s*\\{"));
        String cm = strip(read(java.resolve("Item/armor/client/Costume/Costume_Model.java")));
        List<String[]> armorGeo = chain(method(cm, "public ResourceLocation getModelResource\\(T object\\)\\s*\\{"));
        List<String[]> armorTex = chain(method(cm, "public ResourceLocation getTextureResource\\(T object\\)\\s*\\{"));
        Map<String, String> itemClass = new HashMap<>();
        Matcher ic = Pattern.compile("RegistryObject<(\\w+)>\\s+(\\w+)\\s*=\\s*ITEMS\\.register").matcher(strip(read(java.resolve("Item/ModItemsClothes.java"))));
        while (ic.find()) itemClass.put(ic.group(2), ic.group(1));

        List<Npc> list = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> w = Files.walk(java.resolve("entity"))) { files = w.filter(p -> p.toString().endsWith(".java")).toList(); }
        for (Path f : files) {
            String cls = f.getFileName().toString().replace(".java", "");
            String id = classToId.get(cls);
            if (id == null) continue;
            String src = strip(read(f));
            List<Part> parts = new ArrayList<>();
            String g = resolve(bodyGeo, cls), t = resolve(bodyTex, cls);
            if (g != null && t != null) parts.add(new Part(g, t, List.of()));
            // コスチューム(自分のクラスに無ければ親のクラス)
            Map<String, String> slots = new LinkedHashMap<>();
            for (String c : ancestors(cls)) {
                if (!classFile.containsKey(c)) break;
                Matcher sl = Pattern.compile("setItemSlot\\(EquipmentSlot\\.(HEAD|CHEST|LEGS|FEET)\\s*,\\s*new ItemStack\\(ModItemsClothes\\.(\\w+)\\.get\\(\\)\\)\\)").matcher(strip(read(classFile.get(c))));
                while (sl.find()) slots.putIfAbsent(sl.group(1), sl.group(2));
                if (!slots.isEmpty()) break;
            }
            Map<String, List<String>> bySet = new LinkedHashMap<>(); // geo|tex → 着ている部位
            for (var s : slots.entrySet()) {
                String ac = itemClass.get(s.getValue());
                if (ac == null) continue;
                String[] mt = armorModel(ac);
                String ag = mt != null ? mt[0] : resolve(armorGeo, ac), at = mt != null ? mt[1] : resolve(armorTex, ac);
                if (ag == null || at == null) continue;
                bySet.computeIfAbsent(ag + "|" + at, k -> new ArrayList<>()).add(s.getKey());
            }
            for (var e : bySet.entrySet()) {
                List<String> hide = new ArrayList<>(List.of("armorHead", "armorBody", "armorRightArm", "armorLeftArm", "armorRightLeg", "armorLeftLeg", "armorRightBoot", "armorLeftBoot"));
                for (String slot : e.getValue()) {
                    switch (slot) {
                        case "HEAD" -> hide.remove("armorHead");
                        case "CHEST" -> hide.removeAll(List.of("armorBody", "armorRightArm", "armorLeftArm"));
                        case "LEGS" -> hide.removeAll(List.of("armorRightLeg", "armorLeftLeg"));
                        case "FEET" -> hide.removeAll(List.of("armorRightBoot", "armorLeftBoot"));
                    }
                }
                String[] gt = e.getKey().split("\\|");
                parts.add(new Part(gt[0], gt[1], hide));
            }
            String rel = java.resolve("entity").relativize(f).toString().replace('\\', '/');
            String group = rel.startsWith("Hero/Student") ? "U.A. Students" : rel.startsWith("Hero") ? "Heroes"
                    : rel.startsWith("Villan") ? "Villains" : null;
            if (group == null) continue;
            String name = noFormat(lang("entity.mhc." + id));
            if (name.isEmpty()) continue;
            Matcher s = Pattern.compile("SetEntityStats\\(this\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)((?:\\s*,\\s*\"[a-z0-9_]+\")*)\\s*\\)").matcher(src);
            Matcher l = Pattern.compile("SetEntityStats\\(this\\s*,\\s*\"([a-z0-9_]+)\"\\s*,\\s*(\\d+)\\s*\\)").matcher(src);
            if (s.find()) {
                List<String> q = new ArrayList<>();
                Matcher n = Pattern.compile("\"([a-z0-9_]+)\"").matcher(s.group(6));
                while (n.find()) q.add(n.group(1));
                list.add(new Npc(id, name, group, new int[]{num(s.group(1)), num(s.group(2)), num(s.group(3)), num(s.group(4)), num(s.group(5))}, q, 0, parts));
            } else if (l.find()) {
                list.add(new Npc(id, name, group, null, List.of(l.group(1)), num(l.group(2)), parts));
            } else if (cls.equals("Villan_Mob")) {
                list.add(new Npc(id, name, group, null, List.of("random"), 0, parts));
            }
        }
        list.sort(Comparator.comparing(Npc::group).thenComparing(Npc::name));
        return list;
    }

    // ------------------------------------------------------------------ ページ

    static String rel(int depth) { return depth == 0 ? "" : "../".repeat(depth); }

    static String layout(String title, String desc, String canonical, String main, int depth) {
        String r = rel(depth);
        String full = title.equals(SITE) ? SITE : title + " - " + SITE;
        return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + esc(full) + "</title>\n"
                + "<meta name=\"description\" content=\"" + esc(desc) + "\">\n"
                + "<link rel=\"canonical\" href=\"" + base + "/" + canonical + "\">\n"
                + "<meta property=\"og:title\" content=\"" + esc(full) + "\">\n"
                + "<meta property=\"og:description\" content=\"" + esc(desc) + "\">\n"
                + "<meta property=\"og:type\" content=\"website\">\n"
                + "<meta property=\"og:url\" content=\"" + base + "/" + canonical + "\">\n"
                + "<link rel=\"stylesheet\" href=\"" + r + "style.css\">\n</head>\n<body>\n"
                + "<header class=\"top\"><div class=\"wrap\"><a class=\"brand\" href=\"" + r + "index.html\">My Hero Craft <span>Wiki</span></a>\n"
                + "<nav><a href=\"" + r + "quirks/index.html\">Quirks</a><a href=\"" + r + "characters.html\">Characters</a><a href=\"" + r + "guide.html\">Guide</a></nav></div></header>\n"
                + "<main class=\"wrap\">\n" + main + "\n</main>\n"
                + "<footer class=\"wrap\"><p>Unofficial fan wiki for the Minecraft mod <strong>My Hero Craft [MHC]</strong> (Forge 1.20.1) by kujira. "
                + "Generated from the mod's data. Not affiliated with My Hero Academia, Mojang or Microsoft.</p></footer>\n"
                + "</body>\n</html>\n";
    }

    static String homePage(int quirks, int chars) {
        String main = "<section class=\"hero\"><h1>My Hero Craft Wiki</h1>"
                + "<p class=\"lead\">The guide to <strong>My Hero Craft [MHC]</strong>, a Minecraft Forge 1.20.1 mod that brings the quirks, heroes and villains of My Hero Academia into Minecraft.</p>"
                + "<p class=\"stats\"><span>" + quirks + " quirks</span><span>" + chars + " characters</span></p></section>"
                + "<div class=\"cards\">"
                + card("quirks/index.html", "Quirks", "Every quirk you can get, with its skills, attack power, cooldowns and skill tree.")
                + card("characters.html", "Characters", "Heroes, U.A. students and villains that appear in the world, with their stats and quirks.")
                + card("guide.html", "Guide", "Controls, Hero Mode, skill points, the skill tree and the config file.")
                + "</div>";
        return layout(SITE, "Unofficial wiki for My Hero Craft [MHC], a Minecraft Forge 1.20.1 My Hero Academia mod: every quirk, skill, skill tree, character and control.", "", main, 0);
    }

    static String card(String href, String t, String d) {
        return "<a class=\"card\" href=\"" + href + "\"><h2>" + t + "</h2><p>" + esc(d) + "</p></a>";
    }

    static String quirkIndex(List<String[]> rows) {
        StringBuilder b = new StringBuilder("<h1>Quirks</h1><p class=\"lead\">All " + rows.size() + " quirks in My Hero Craft. Type to search by quirk or user.</p>"
                + "<input id=\"q\" class=\"search\" type=\"search\" placeholder=\"Search quirks (e.g. Explosion, Bakugo)\" aria-label=\"Search quirks\">");
        String cat = null;
        for (String[] r : rows) {
            if (!r[2].equals(cat)) {
                if (cat != null) b.append("</div>");
                cat = r[2];
                b.append("<h2 class=\"cat\">").append(esc(cat)).append("</h2><div class=\"grid\">");
            }
            b.append("<a class=\"item\" href=\"").append(r[0]).append(".html\" data-s=\"").append(esc((r[1] + " " + r[3] + " " + r[0]).toLowerCase())).append("\">")
                    .append("<strong>").append(esc(r[1])).append("</strong>")
                    .append(r[3].isEmpty() ? "" : "<span class=\"users\">" + esc(r[3]) + "</span>")
                    .append(r[4].isEmpty() ? "" : "<span class=\"desc\">" + esc(r[4]) + "</span>")
                    .append("</a>");
        }
        if (cat != null) b.append("</div>");
        b.append("<script src=\"../search.js\"></script>");
        return layout("Quirks", "List of every quirk in the My Hero Craft Minecraft mod, grouped into U.A. students, heroes, villains, vigilantes and others.", "quirks/", b.toString(), 1);
    }

    static String quirkPage(String q, Kosei k, String cat, Tree t, Cd cd, Set<String> dispatched, List<Npc> npcs) {
        String desc = plain(lang("mhc.chat.message.note." + q + "_desc"));
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"crumb\"><a href=\"index.html\">Quirks</a> / ").append(esc(cat)).append("</p>");
        b.append("<h1>").append(esc(k.name)).append("</h1>");
        if (!k.users.isEmpty()) b.append("<p class=\"users\">Used by: ").append(esc(k.users)).append("</p>");
        if (!desc.isEmpty()) b.append("<p class=\"lead\">").append(esc(desc)).append("</p>");

        List<Node> passives = t.nodes.stream().filter(n -> n.passive).toList();
        List<Node> skills = t.nodes.stream().filter(n -> !n.passive).toList();
        if (!passives.isEmpty()) {
            b.append("<h2>Passives</h2><ul class=\"passives\">");
            for (Node n : passives) {
                String[] p = splitNote(lang("mhc.chat.message.note." + n.id));
                String title = p != null ? noFormat(p[0]) : titleCase(n.id);
                b.append("<li><strong>").append(esc(title)).append("</strong> ").append(esc(plain(lang("mhc.chat.message.note." + n.id)))).append("</li>");
            }
            b.append("</ul>");
        }
        if (!skills.isEmpty()) {
            b.append("<h2>Skills</h2><div class=\"table\"><table><thead><tr><th>Skill</th><th>Description</th><th>Attack</th><th>Cooldown (s)</th><th>Points</th><th>Unlocked after</th></tr></thead><tbody>");
            for (Node n : skills) {
                String note = lang("mhc.chat.message.note." + n.id);
                String name = skillName(n.id);
                String pre = n.pre.stream().map(p -> p == t.root ? "—" : skillName(p.id)).collect(Collectors.joining(", "));
                String frame = n.frame.equals("GOAL") ? " <span class=\"tag goal\">Goal</span>" : n.frame.equals("CHALLENGE") ? " <span class=\"tag challenge\">Challenge</span>" : "";
                b.append("<tr><td><strong>").append(esc(name)).append("</strong>").append(frame).append("</td><td>").append(esc(plain(note)))
                        .append("</td><td>").append(esc(atk(note))).append("</td><td>").append(esc(cd.of(n.id))).append("</td><td>")
                        .append(n.cost).append("</td><td>").append(esc(pre.isEmpty() ? "—" : pre)).append("</td></tr>");
            }
            b.append("</tbody></table></div>");
            b.append("<h2>Skill tree</h2><p class=\"note\">Skills unlock in this order. Spend skill points in the skill tree screen (Quirk Note) to learn them.</p>");
            b.append("<ul class=\"tree\">");
            for (Node c : t.root.children) if (!c.passive) treeItem(b, c, new HashSet<>());
            b.append("</ul>");
        }
        List<Npc> users = npcs.stream().filter(n -> n.quirks().contains(q)).toList();
        if (!users.isEmpty()) {
            b.append("<h2>Characters with this quirk</h2><ul class=\"inline\">");
            for (Npc n : users) b.append("<li><a href=\"../characters/").append(n.id()).append(".html\">").append(esc(label(n))).append("</a></li>");
            b.append("</ul>");
        }
        String meta = k.name + " is a quirk in the My Hero Craft Minecraft mod" + (k.users.isEmpty() ? "" : " (used by " + k.users + ")") + ". "
                + (desc.isEmpty() ? "" : desc + " ") + "Skills, attack power, cooldowns and skill tree.";
        return layout(k.name + " (Quirk)", meta.length() > 300 ? meta.substring(0, 297) + "..." : meta, "quirks/" + q + ".html", b.toString(), 1);
    }

    static void treeItem(StringBuilder b, Node n, Set<Node> seen) {
        b.append("<li><span>").append(esc(skillName(n.id))).append("</span>");
        if (!seen.add(n)) { b.append("</li>"); return; }
        List<Node> kids = n.children.stream().filter(c -> !c.passive).toList();
        if (!kids.isEmpty()) {
            b.append("<ul>");
            for (Node c : kids) treeItem(b, c, seen);
            b.append("</ul>");
        }
        b.append("</li>");
    }

    static String skillName(String id) {
        String q = noFormat(lang("mhc.chat.message.quark." + id));
        if (!q.isEmpty()) return q.replace("%s", "Target");
        String[] p = splitNote(lang("mhc.chat.message.note." + id));
        return p != null ? noFormat(p[0]) : titleCase(id);
    }

    /** 同じ名前の別バージョン(student_midoriya_2 など)は番号を付けて見分ける。 */
    static String label(Npc n) {
        Matcher m = Pattern.compile("_(\\d+)$").matcher(n.id());
        return m.find() ? n.name() + " (" + m.group(1) + ")" : n.name();
    }

    static String charactersPage(List<Npc> npcs) {
        StringBuilder b = new StringBuilder("<h1>Characters</h1><p class=\"lead\">Characters from My Hero Academia that appear in My Hero Craft. "
                + "Click a character to see their model, quirk and stats.</p>");
        String grp = null;
        for (Npc n : npcs) {
            if (!n.group().equals(grp)) {
                if (grp != null) b.append("</div>");
                grp = n.group();
                b.append("<h2>").append(esc(grp)).append("</h2><div class=\"names\">");
            }
            b.append("<a href=\"characters/").append(n.id()).append(".html\">").append(esc(label(n))).append("</a>");
        }
        if (grp != null) b.append("</div>");
        return layout("Characters", "Heroes, U.A. students and villains in the My Hero Craft Minecraft mod.", "characters.html", b.toString(), 0);
    }

    static String charPage(Npc n, Map<String, Tree> trees) {
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"crumb\"><a href=\"../characters.html\">Characters</a> / ").append(esc(n.group())).append("</p>");
        b.append("<h1>").append(esc(label(n))).append("</h1>");
        String quirks = n.quirks().stream().map(q -> q.equals("random") ? "Random (any quirk)" : trees.containsKey(q)
                ? "<a href=\"../quirks/" + q + ".html\">" + esc(kosei(q).name()) + "</a>" : esc(kosei(q).name())).collect(Collectors.joining(", "));
        b.append("<p class=\"lead\">Quirk: ").append(quirks).append("</p>");
        b.append("<div class=\"char\">");
        // 3D モデル
        b.append("<div class=\"panel\"><h2>Model</h2>");
        if (!n.parts().isEmpty()) {
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < n.parts().size(); i++) {
                Part p = n.parts().get(i);
                json.append(i > 0 ? "," : "").append("{\"geo\":\"../assets/").append(p.geo()).append("\",\"tex\":\"../assets/").append(p.tex())
                        .append("\",\"hide\":[").append(p.hide().stream().map(h -> "\"" + h + "\"").collect(Collectors.joining(","))).append("]}");
            }
            b.append("<div id=\"viewer\" class=\"viewer\" aria-label=\"3D model of ").append(esc(n.name())).append("\"></div>")
                    .append("<p class=\"note\">Drag to rotate, scroll to zoom.</p>")
                    .append("<script id=\"model-data\" type=\"application/json\">").append(json).append("]</script>")
                    .append("<script>if(location.protocol==='file:'){document.getElementById('viewer').innerHTML='<p class=\"note\" style=\"padding:16px\">The 3D model cannot load from a file on your PC. Open the wiki with preview.bat or on the website.</p>';}</script>");
        } else {
            b.append("<p class=\"note\">No model data.</p>");
        }
        b.append("</div>");
        // ステータス
        b.append("<div class=\"panel\"><h2>Stats</h2>");
        String[] labels = {"Health", "Strength", "Dexterity", "Defense", "Quirk"};
        if (n.stats() != null) {
            b.append(radar(n.stats(), labels));
            b.append("<div class=\"bars\">");
            for (int i = 0; i < 5; i++) {
                int v = n.stats()[i];
                b.append("<div class=\"bar\"><span>").append(labels[i]).append("</span><div><i style=\"width:").append(Math.min(100, v * 5)).append("%\"></i></div><b>").append(v).append("</b></div>");
            }
            b.append("</div><p class=\"note\">Each stat goes up to 20. Defeating this character alone gives about ")
                    .append(Math.max(1, Math.round(Arrays.stream(n.stats()).sum() / 4.0))).append(" skill points.</p>");
        } else if (n.quirks().contains("random")) {
            b.append("<p>Each stat is random from 0 to 4. Gives about 2–3 skill points (up to 5).</p>");
        } else {
            b.append("<p>Quirk level ").append(n.level()).append(".</p>");
        }
        b.append("</div></div>");
        if (!n.parts().isEmpty()) {
            b.append("<script type=\"importmap\">{\"imports\":{\"three\":\"https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js\","
                    + "\"three/addons/\":\"https://cdn.jsdelivr.net/npm/three@0.160.0/examples/jsm/\"}}</script>")
                    .append("<script type=\"module\" src=\"../viewer.js\"></script>");
        }
        return layout(label(n) + " (Character)", n.name() + " in the My Hero Craft Minecraft mod: quirk, stats and 3D model.",
                "characters/" + n.id() + ".html", b.toString(), 1);
    }

    /** 5つのステータスのレーダーチャート(SVG)。最大20。 */
    static String radar(int[] v, String[] labels) {
        double cx = 150, cy = 140, r = 100;
        StringBuilder s = new StringBuilder("<svg class=\"radar\" viewBox=\"0 0 300 290\" role=\"img\" aria-label=\"Stats chart\">");
        for (int ring = 1; ring <= 4; ring++) {
            StringBuilder pts = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                double a = -Math.PI / 2 + i * 2 * Math.PI / 5, rr = r * ring / 4;
                pts.append(String.format(Locale.ROOT, "%.1f,%.1f ", cx + rr * Math.cos(a), cy + rr * Math.sin(a)));
            }
            s.append("<polygon class=\"ring\" points=\"").append(pts).append("\"/>");
        }
        StringBuilder val = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            double a = -Math.PI / 2 + i * 2 * Math.PI / 5;
            s.append(String.format(Locale.ROOT, "<line class=\"axis\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\"/>", cx, cy, cx + r * Math.cos(a), cy + r * Math.sin(a)));
            double rr = r * Math.min(20, v[i]) / 20.0;
            val.append(String.format(Locale.ROOT, "%.1f,%.1f ", cx + rr * Math.cos(a), cy + rr * Math.sin(a)));
            double lx = cx + (r + 22) * Math.cos(a), ly = cy + (r + 18) * Math.sin(a);
            s.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s %d</text>", lx, ly + 4, labels[i], v[i]));
        }
        s.append("<polygon class=\"val\" points=\"").append(val).append("\"/></svg>");
        return s.toString();
    }

    static String guidePage() {
        String main = "<h1>Guide</h1>"
                + "<h2 id=\"controls\">Controls</h2><div class=\"table\"><table><thead><tr><th>Key</th><th>Action</th></tr></thead><tbody>"
                + row("H", "Toggle Hero Mode. Most quirk skills are used in Hero Mode.")
                + row("Left click", "Use the selected skill (hold to charge skills that can be charged).")
                + row("R / mouse wheel", "Switch the selected skill.")
                + row("1 – 9 (Hero Mode)", "Select the skill in that slot directly.")
                + row("C (Hero Mode)", "Blink in the direction you are moving. 10 second cooldown, shown above the hunger bar.")
                + row("N", "Open the Quirk Note (skill tree).")
                + row("K", "Open the status screen to spend status points.")
                + row("Shift twice", "Triggers some passives (for example climbing). Only works while a skill of that quirk is selected.")
                + "</tbody></table></div><p class=\"note\">All keys can be changed in Options → Controls → Key Binds.</p>"
                + "<h2 id=\"modes\">Hero and Villain worlds</h2><p>Each player is either on the hero side or the villain side, decided the first time they join a world "
                + "(the <code>mhcVillainMode</code> game rule decides it for new players). Defeating characters of the other side gives skill points.</p>"
                + "<h2 id=\"points\">Skill points</h2><p>When you defeat a hero or villain of the other side, every player who damaged it gets skill points:</p>"
                + "<p class=\"formula\">points = (sum of the enemy's 5 stats) ÷ 4 × (your damage ÷ enemy max health) × config multiplier</p>"
                + "<p>You always get at least 1 point if you dealt damage. For example, defeating Sir Nighteye alone gives about 20 points, and a random villain gives about 2–3 (up to 5). "
                + "In Creative mode, points are unlimited.</p>"
                + "<h2 id=\"tree\">Skill tree</h2><p>Open the Quirk Note to see your quirk's skill tree. Passives are unlocked from the start. "
                + "Each skill costs points and needs the skill before it. Skills marked <span class=\"tag goal\">Goal</span> or <span class=\"tag challenge\">Challenge</span> are the strongest ones.</p>"
                + "<h2 id=\"cooldowns\">Cooldowns</h2><p>After you use a skill it has a cooldown and cannot be used again until it ends. Cooldowns reset when you respawn.</p>"
                + "<h2 id=\"config\">Config (mhc-common.toml)</h2><div class=\"table\"><table><thead><tr><th>Option</th><th>Default</th><th>What it does</th></tr></thead><tbody>"
                + row3("Kosei", "false", "Ignore the activation conditions of certain quirks.")
                + row3("ParadeMaxGeneration", "7", "How many generations of clones Sad Man's Parade can make.")
                + row3("Monster / Villain / Hero", "false / true / true", "Whether vanilla monsters, villains and heroes spawn.")
                + row3("Flash / Distortion / Shake / Chromatic", "true", "Screen effects of big skills.")
                + row3("Exp", "1.0", "Experience multiplier when defeating villains.")
                + row3("Points", "1.0", "Skill point multiplier when defeating characters of the other side.")
                + row3("FriendlyFire", "false", "Whether skills can hurt allies.")
                + "</tbody></table></div>";
        return layout("Guide", "How to play My Hero Craft: controls, Hero Mode, blink, skill points, skill tree, cooldowns and config options.", "guide.html", main, 0);
    }

    static String row(String a, String b) { return "<tr><td><kbd>" + esc(a) + "</kbd></td><td>" + esc(b) + "</td></tr>"; }

    static String row3(String a, String b, String c) { return "<tr><td><code>" + esc(a) + "</code></td><td>" + esc(b) + "</td><td>" + esc(c) + "</td></tr>"; }

    static final String JS = """
            const box = document.getElementById('q');
            if (box) box.addEventListener('input', () => {
              const v = box.value.trim().toLowerCase();
              document.querySelectorAll('.item').forEach(a => { a.style.display = !v || a.dataset.s.includes(v) ? '' : 'none'; });
              document.querySelectorAll('.grid').forEach(g => {
                const any = [...g.children].some(c => c.style.display !== 'none');
                g.style.display = any ? '' : 'none';
                const h = g.previousElementSibling; if (h) h.style.display = any ? '' : 'none';
              });
            });
            """;

    /** Bedrock(GeckoLib)のモデルを three.js で描く。体とコスチュームを重ねる。 */
    static final String VIEWER = """
            import * as THREE from 'three';
            import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
            const el = document.getElementById('viewer');
            const parts = JSON.parse(document.getElementById('model-data').textContent);
            const D = Math.PI / 180;
            const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
            renderer.setPixelRatio(Math.min(2, window.devicePixelRatio));
            el.appendChild(renderer.domElement);
            const scene = new THREE.Scene();
            const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 1000);
            camera.position.set(-30, 22, -92);
            const controls = new OrbitControls(camera, renderer.domElement);
            controls.target.set(0, 16, 0);
            controls.enablePan = false;
            controls.minDistance = 20;
            controls.maxDistance = 140;
            controls.autoRotate = true;
            controls.autoRotateSpeed = 1.2;
            controls.addEventListener('start', () => { controls.autoRotate = false; });
            const loader = new THREE.TextureLoader();

            function rect(u0, v0, u1, v1, flipU, flipV) { return flipU ? (flipV ? [u1, v1, u0, v0] : [u1, v0, u0, v1]) : (flipV ? [u0, v1, u1, v0] : [u0, v0, u1, v1]); }

            function faceRects(c) {
              const [w, h, d] = c.size;
              if (Array.isArray(c.uv) || !c.uv) {
                const [u, v] = c.uv || [0, 0];
                let east = rect(u, v + d, u + d, v + d + h), west = rect(u + d + w, v + d, u + 2 * d + w, v + d + h);
                const north = rect(u + d, v + d, u + d + w, v + d + h), south = rect(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h);
                const up = rect(u + d, v, u + d + w, v + d, true, true), down = rect(u + d + w, v, u + d + 2 * w, v + d, true, false);
                let r = [east, west, up, down, south, north];
                if (c.mirror) { r = [west, east, up, down, south, north].map(q => [q[2], q[1], q[0], q[3]]); }
                return r;
              }
              const f = n => { const o = c.uv[n]; if (!o) return null; const [u, v] = o.uv, [sw, sh] = o.uv_size || [0, 0]; return [u, v, u + sw, v + sh]; };
              const up = f('up'), down = f('down');
              return [f('east'), f('west'), up && [up[2], up[3], up[0], up[1]], down && [down[2], down[1], down[0], down[3]], f('south'), f('north')];
            }

            function cubeMesh(c, mat, tw, th) {
              const inf = c.inflate || 0, [w, h, d] = c.size;
              const geo = new THREE.BoxGeometry(Math.max(w + inf * 2, 0.001), Math.max(h + inf * 2, 0.001), Math.max(d + inf * 2, 0.001));
              const uv = geo.attributes.uv, rs = faceRects(c);
              const hidden = [];
              rs.forEach((r, i) => {
                if (!r) { hidden.push(i); return; }
                const [u0, v0, u1, v1] = r;
                uv.setXY(i * 4 + 0, u0 / tw, 1 - v0 / th); uv.setXY(i * 4 + 1, u1 / tw, 1 - v0 / th);
                uv.setXY(i * 4 + 2, u0 / tw, 1 - v1 / th); uv.setXY(i * 4 + 3, u1 / tw, 1 - v1 / th);
              });
              if (hidden.length) { const idx = geo.index.array; hidden.forEach(i => { for (let k = 0; k < 6; k++) idx[i * 6 + k] = 0; }); }
              return new THREE.Mesh(geo, mat);
            }

            function build(json, tex, hide) {
              const g = (json['minecraft:geometry'] || [])[0];
              if (!g) return null;
              const tw = g.description.texture_width, th = g.description.texture_height;
              const mat = new THREE.MeshBasicMaterial({ map: tex, alphaTest: 0.1, side: THREE.DoubleSide });
              const root = new THREE.Group(), bones = {};
              for (const b of g.bones || []) bones[b.name] = { b, grp: new THREE.Group() };
              const pv = b => { const p = b.pivot || [0, 0, 0]; return [-p[0], p[1], p[2]]; };
              for (const { b, grp } of Object.values(bones)) {
                const p = pv(b), parent = b.parent && bones[b.parent];
                const pp = parent ? pv(parent.b) : [0, 0, 0];
                grp.position.set(p[0] - pp[0], p[1] - pp[1], p[2] - pp[2]);
                const r = b.rotation || [0, 0, 0];
                grp.rotation.set(-r[0] * D, -r[1] * D, r[2] * D, 'ZYX');
                (parent ? parent.grp : root).add(grp);
                if (hide.includes(b.name)) grp.visible = false;
                for (const c of b.cubes || []) {
                  const [ox, oy, oz] = c.origin, [w, h, d] = c.size;
                  const center = [-(ox + w / 2), oy + h / 2, oz + d / 2];
                  const mesh = cubeMesh(c, mat, tw, th);
                  if (c.rotation) {
                    const cp = c.pivot ? [-c.pivot[0], c.pivot[1], c.pivot[2]] : center;
                    const holder = new THREE.Group();
                    holder.position.set(cp[0] - p[0], cp[1] - p[1], cp[2] - p[2]);
                    holder.rotation.set(-c.rotation[0] * D, -c.rotation[1] * D, c.rotation[2] * D, 'ZYX');
                    mesh.position.set(center[0] - cp[0], center[1] - cp[1], center[2] - cp[2]);
                    holder.add(mesh); grp.add(holder);
                  } else {
                    mesh.position.set(center[0] - p[0], center[1] - p[1], center[2] - p[2]);
                    grp.add(mesh);
                  }
                }
              }
              return root;
            }

            for (const p of parts) {
              try {
                const [json, tex] = await Promise.all([fetch(p.geo).then(r => r.json()), loader.loadAsync(p.tex)]);
                tex.magFilter = THREE.NearestFilter; tex.minFilter = THREE.NearestFilter; tex.colorSpace = THREE.SRGBColorSpace;
                const m = build(json, tex, p.hide || []);
                if (m) scene.add(m);
              } catch (e) { console.warn('model part failed', p, e); }
            }
            function resize() { const w = el.clientWidth, h = el.clientHeight; renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix(); }
            new ResizeObserver(resize).observe(el); resize();
            renderer.setAnimationLoop(() => { controls.update(); renderer.render(scene, camera); });
            """;

    static final String CSS = """
            :root{--bg:#f7f8fb;--panel:#fff;--ink:#14171f;--muted:#5b6475;--line:#e3e6ee;--accent:#1f7a52;--accent2:#e8b21a;--chal:#b4234a}
            @media (prefers-color-scheme:dark){:root{--bg:#10131a;--panel:#171b24;--ink:#e8ebf2;--muted:#9aa3b5;--line:#272d3a;--accent:#4cc58c;--accent2:#f1c24a;--chal:#ef6d8f}}
            *{box-sizing:border-box}html{-webkit-text-size-adjust:100%}
            body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
            a{color:var(--accent)}.wrap{max-width:1100px;margin:0 auto;padding:0 16px}
            .top{background:var(--panel);border-bottom:1px solid var(--line);position:sticky;top:0;z-index:5}
            .top .wrap{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:56px;flex-wrap:wrap}
            .brand{font-weight:800;font-size:18px;color:var(--ink);text-decoration:none}.brand span{color:var(--accent)}
            nav a{margin-left:16px;text-decoration:none;color:var(--ink);font-weight:600}nav a:hover{color:var(--accent)}
            main{padding:24px 16px 48px}h1{font-size:32px;line-height:1.2;margin:8px 0 8px}h2{margin-top:32px;font-size:22px}
            .lead{font-size:18px;color:var(--muted);max-width:760px}.users{color:var(--muted)}
            .hero{padding:24px 0}.stats span{display:inline-block;margin-right:12px;padding:4px 10px;border-radius:999px;background:var(--panel);border:1px solid var(--line);font-weight:600}
            .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px}
            .card{display:block;padding:20px;background:var(--panel);border:1px solid var(--line);border-radius:12px;text-decoration:none;color:var(--ink)}
            .card:hover{border-color:var(--accent)}.card h2{margin:0 0 6px;color:var(--accent)}.card p{margin:0;color:var(--muted)}
            .search{width:100%;max-width:520px;padding:10px 14px;font-size:16px;border:1px solid var(--line);border-radius:10px;background:var(--panel);color:var(--ink)}
            .cat{margin-top:28px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:12px}
            .item{display:flex;flex-direction:column;gap:2px;padding:14px;background:var(--panel);border:1px solid var(--line);border-radius:10px;text-decoration:none;color:var(--ink)}
            .item:hover{border-color:var(--accent)}.item .users{font-size:13px}.item .desc{font-size:14px;color:var(--muted)}
            .crumb{color:var(--muted);margin:0}.table{overflow-x:auto;border:1px solid var(--line);border-radius:10px;background:var(--panel)}
            table{border-collapse:collapse;width:100%;font-size:15px}th,td{padding:10px 12px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
            th{font-size:13px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted);white-space:nowrap}tr:last-child td{border-bottom:0}
            .tag{display:inline-block;font-size:12px;font-weight:700;padding:1px 8px;border-radius:999px;margin-left:4px;vertical-align:middle}
            .goal{background:color-mix(in srgb,var(--accent2) 25%,transparent);color:var(--ink)}.challenge{background:color-mix(in srgb,var(--chal) 22%,transparent);color:var(--ink)}
            .passives li{margin-bottom:6px}.note{color:var(--muted);font-size:14px}
            .tree,.tree ul{list-style:none;padding-left:18px;margin:0}.tree{padding-left:0}
            .tree li{position:relative;padding:3px 0 3px 14px;border-left:2px solid var(--line)}.tree li>span{display:inline-block;padding:2px 10px;border-radius:8px;background:var(--panel);border:1px solid var(--line)}
            .inline{list-style:none;padding:0;display:flex;flex-wrap:wrap;gap:8px}.inline li a{display:inline-block;padding:4px 10px;border:1px solid var(--line);border-radius:999px;background:var(--panel);text-decoration:none}
            .formula{padding:12px 16px;background:var(--panel);border:1px solid var(--line);border-radius:10px;font-weight:600}
            kbd,code{font-family:ui-monospace,Consolas,monospace;font-size:14px;background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:1px 6px}
            footer{color:var(--muted);font-size:14px;padding:24px 16px 40px;border-top:1px solid var(--line)}
            .names{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}
            .names a{display:block;padding:10px 14px;background:var(--panel);border:1px solid var(--line);border-radius:10px;text-decoration:none;color:var(--ink);font-weight:600}
            .names a:hover{border-color:var(--accent);color:var(--accent)}
            .char{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:16px;align-items:start}
            .panel{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:16px}.panel h2{margin:0 0 8px;font-size:18px}
            .viewer{width:100%;aspect-ratio:3/4;max-height:560px;border-radius:10px;background:radial-gradient(circle at 50% 40%,color-mix(in srgb,var(--accent) 14%,transparent),transparent 70%)}
            .viewer canvas{width:100%;height:100%;display:block;cursor:grab}
            .radar{width:100%;max-width:340px;display:block;margin:0 auto}.radar .ring{fill:none;stroke:var(--line)}.radar .axis{stroke:var(--line)}
            .radar .val{fill:color-mix(in srgb,var(--accent) 30%,transparent);stroke:var(--accent);stroke-width:2}.radar text{fill:var(--muted);font-size:12px}
            .bars{display:grid;gap:6px;margin-top:8px}.bar{display:grid;grid-template-columns:80px 1fr 28px;align-items:center;gap:8px;font-size:14px}
            .bar div{height:8px;border-radius:999px;background:var(--line);overflow:hidden}.bar i{display:block;height:100%;background:var(--accent)}.bar b{text-align:right}
            @media (max-width:760px){.char{grid-template-columns:1fr}.names{grid-template-columns:repeat(2,minmax(0,1fr))}}
            @media (max-width:600px){h1{font-size:26px}nav a{margin-left:12px}}
            """;
}
