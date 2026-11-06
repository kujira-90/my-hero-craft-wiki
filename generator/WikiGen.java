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
        write("characters.html", charactersPage(npcs, trees));
        urls.add("characters.html");
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

    record Npc(String id, String name, String group, int[] stats, List<String> quirks, int level) {}

    static List<Npc> npcs(Path java) throws Exception {
        String reg = strip(read(java.resolve("entity/ModEntityType.java")));
        Map<String, String> classToId = new HashMap<>();
        Matcher m = Pattern.compile("register\\(\"([a-z0-9_]+)\",\\s*EntityType\\.Builder\\.(?:<\\w+>)?of\\((\\w+)::new").matcher(reg);
        while (m.find()) classToId.putIfAbsent(m.group(2), m.group(1));
        List<Npc> list = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> w = Files.walk(java.resolve("entity"))) { files = w.filter(p -> p.toString().endsWith(".java")).toList(); }
        for (Path f : files) {
            String cls = f.getFileName().toString().replace(".java", "");
            String id = classToId.get(cls);
            if (id == null) continue;
            String src = strip(read(f));
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
                list.add(new Npc(id, name, group, new int[]{num(s.group(1)), num(s.group(2)), num(s.group(3)), num(s.group(4)), num(s.group(5))}, q, 0));
            } else if (l.find()) {
                list.add(new Npc(id, name, group, null, List.of(l.group(1)), num(l.group(2))));
            } else if (cls.equals("Villan_Mob")) {
                list.add(new Npc(id, name, group, null, List.of("random"), 0));
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
            for (Npc n : users) b.append("<li><a href=\"../characters.html#").append(n.id()).append("\">").append(esc(n.name())).append("</a></li>");
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

    static String charactersPage(List<Npc> npcs, Map<String, Tree> trees) {
        StringBuilder b = new StringBuilder("<h1>Characters</h1><p class=\"lead\">Characters from My Hero Academia that appear in My Hero Craft. "
                + "Stats are Health, Strength, Dexterity, Defense and Quirk level.</p>");
        String grp = null;
        for (Npc n : npcs) {
            if (!n.group().equals(grp)) {
                if (grp != null) b.append("</tbody></table></div>");
                grp = n.group();
                b.append("<h2>").append(esc(grp)).append("</h2><div class=\"table\"><table><thead><tr><th>Name</th><th>Quirk</th><th>HP</th><th>STR</th><th>DEX</th><th>DEF</th><th>Quirk Lv</th></tr></thead><tbody>");
            }
            String quirks = n.quirks().stream().map(q -> q.equals("random") ? "Random" : trees.containsKey(q)
                    ? "<a href=\"quirks/" + q + ".html\">" + esc(kosei(q).name()) + "</a>" : esc(kosei(q).name())).collect(Collectors.joining(", "));
            b.append("<tr id=\"").append(n.id()).append("\"><td><strong>").append(esc(n.name())).append("</strong></td><td>").append(quirks).append("</td>");
            if (n.stats() != null) for (int s : n.stats()) b.append("<td>").append(s).append("</td>");
            else if (n.quirks().contains("random")) b.append("<td colspan=\"5\">Random (0–4 each)</td>");
            else b.append("<td colspan=\"5\">Level ").append(n.level()).append("</td>");
            b.append("</tr>");
        }
        if (grp != null) b.append("</tbody></table></div>");
        return layout("Characters", "Heroes, U.A. students and villains in the My Hero Craft Minecraft mod, with their quirks and stats.", "characters.html", b.toString(), 0);
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
                + row("X / Z", "Use support items (torso / legs). Hold Shift for head / feet.")
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
            @media (max-width:600px){h1{font-size:26px}nav a{margin-left:12px}}
            """;
}
