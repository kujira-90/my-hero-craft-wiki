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
        for (Npc n : npcs) {
            write("characters/" + n.id() + ".html", charPage(n, trees));
            urls.add("characters/" + n.id() + ".html");
        }
        write("guide.html", guidePage());
        urls.add("guide.html");
        // 店(発目・ランチラッシュ)・アイテム(サポートアイテム・コスチューム)・スマホ
        String catalog = strip(read(java.resolve("Item/CostumeCatalog.java")));
        String modItems = strip(read(java.resolve("Item/ModItems.java")));
        String office = strip(read(java.resolve("office/OfficeData.java")));
        List<String[]> support = new ArrayList<>(); // id, 値段
        Matcher sm2 = Pattern.compile("new SupportItem\\(ModItems\\.(\\w+)\\s*,\\s*(\\d+)\\)").matcher(catalog);
        while (sm2.find()) {
            Matcher f = Pattern.compile(sm2.group(1) + "\\s*=\\s*ITEMS\\.register\\(\"([a-z0-9_]+)\"").matcher(modItems);
            if (f.find()) support.add(new String[]{f.group(1), sm2.group(2)});
        }
        Map<String, Integer> slotCost = new LinkedHashMap<>();
        Matcher sc = Pattern.compile("case (HELMET|CHESTPLATE|LEGGINGS|BOOTS) -> (\\d+);").matcher(catalog);
        while (sc.find()) slotCost.put(sc.group(1), num(sc.group(2)));
        List<String[]> costumes = new ArrayList<>(); // id, 部位
        String clothes = strip(read(java.resolve("Item/ModItemsClothes.java")));
        Matcher cr = Pattern.compile("ITEMS\\.register\\(\\s*\"((?:costume|ua)_[a-z0-9_]+)\"(.{0,400}?)ArmorItem\\.Type\\.(\\w+)", Pattern.DOTALL).matcher(clothes);
        while (cr.find()) if (!cr.group(1).contains("armored_allmight") && !cr.group(2).contains("ITEMS.register")) costumes.add(new String[]{cr.group(1), cr.group(3)});
        int appCost = 32, tubeCost = 40;
        Matcher ac = Pattern.compile("APP_COST\\s*=\\s*(\\d+)").matcher(office);
        if (ac.find()) appCost = num(ac.group(1));
        Matcher tc = Pattern.compile("equals\\(\"herotube\"\\)\\s*\\?\\s*(\\d+)").matcher(office);
        if (tc.find()) tubeCost = num(tc.group(1));
        List<String> apps = new ArrayList<>();
        Matcher ap = Pattern.compile("APPS\\s*=\\s*List\\.of\\(([^)]*)\\)").matcher(office);
        if (ap.find()) { Matcher q = Pattern.compile("\"(\\w+)\"").matcher(ap.group(1)); while (q.find()) apps.add(q.group(1)); }
        Map<String, List<String[]>> channels = new LinkedHashMap<>(); // チャンネル → (技, ポイント)
        String chSrc = office.substring(Math.max(0, office.indexOf("CHANNELS")));
        Matcher ch = Pattern.compile("new Channel\\(\"(\\w+)\"|new Video\\(\"(\\w+)\"\\s*,\\s*(\\d+)\\)").matcher(chSrc);
        String cur = null;
        while (ch.find()) {
            if (ch.group(1) != null) { cur = ch.group(1); channels.put(cur, new ArrayList<>()); }
            else if (cur != null) channels.get(cur).add(new String[]{ch.group(2), ch.group(3)});
        }
        write("shops.html", shopsPage(support, slotCost, appCost, tubeCost));
        urls.add("shops.html");
        write("items.html", itemsPage(support, costumes, slotCost));
        urls.add("items.html");
        write("smartphone.html", phonePage(apps, appCost, tubeCost, channels, support));
        urls.add("smartphone.html");
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

    /** Renderer のクラスから Model を探し、target のクラスで {geo, texture} を決める。 */
    @SuppressWarnings("unchecked")
    static String[] fromRenderer(String renderer, String target) throws Exception {
        Path rf = classFile.get(renderer);
        if (rf == null) return null;
        Matcher mm = Pattern.compile("new\\s+(\\w+)\\s*(?:<[^>]*>)?\\s*\\(").matcher(strip(read(rf)));
        while (mm.find()) {
            String model = mm.group(1);
            Path mf = classFile.get(model);
            if (mf == null) continue;
            String ms = strip(read(mf));
            if (!ms.contains("getModelResource")) continue;
            List<String[]>[] ch = modelChains.get(model);
            if (ch == null) {
                String texBody = method(ms, "public ResourceLocation getTextureResource\\(\\w+ \\w+\\)\\s*\\{");
                if (texBody.contains("textureResource(")) texBody = method(ms, "ResourceLocation textureResource\\(\\w+ \\w+\\)\\s*\\{");
                ch = new List[]{chain(method(ms, "public ResourceLocation getModelResource\\(\\w+ \\w+\\)\\s*\\{")), chain(texBody)};
                modelChains.put(model, ch);
            }
            String g = resolve(ch[0], target), t = resolve(ch[1], target);
            return g != null && t != null ? new String[]{g, t} : null;
        }
        return null;
    }

    /** コスチュームの item クラス → 使っている Renderer → Model を読んで、{geo, texture} を返す。分からなければ null。 */
    @SuppressWarnings("unchecked")
    static String[] armorModel(String itemCls) throws Exception {
        for (String c : ancestors(itemCls)) {
            Path f = classFile.get(c);
            if (f == null) return null;
            Matcher r = Pattern.compile("new\\s+(\\w*Renderer)\\s*\\(").matcher(strip(read(f)));
            if (!r.find() || !classFile.containsKey(r.group(1))) continue;
            return fromRenderer(r.group(1), itemCls);
        }
        return null;
    }

    static List<Npc> npcs(Path java) throws Exception {
        String reg = strip(read(java.resolve("entity/ModEntityType.java")));
        Map<String, String> classToId = new HashMap<>();
        Matcher m = Pattern.compile("register\\(\"([a-z0-9_]+)\",\\s*EntityType\\.Builder\\.(?:<\\w+>)?of\\((\\w+)::new").matcher(reg);
        while (m.find()) classToId.putIfAbsent(m.group(2), m.group(1));
        // エンティティのクラス → 描く Renderer(ModEntityType の registerEntityRenderer)
        Map<String, String> fieldToClass = new HashMap<>(), classToRenderer = new HashMap<>();
        m = Pattern.compile("RegistryObject<EntityType<(\\w+)>>\\s+(\\w+)\\s*=").matcher(reg);
        while (m.find()) fieldToClass.put(m.group(2), m.group(1));
        m = Pattern.compile("registerEntityRenderer\\(\\s*ModEntityType\\.(\\w+)\\.get\\(\\)\\s*,\\s*(\\w+)::new").matcher(reg);
        while (m.find()) if (fieldToClass.containsKey(m.group(1))) classToRenderer.put(fieldToClass.get(m.group(1)), m.group(2));
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
            String[] body = classToRenderer.containsKey(cls) ? fromRenderer(classToRenderer.get(cls), cls) : null;
            String g = body != null ? body[0] : resolve(bodyGeo, cls), t = body != null ? body[1] : resolve(bodyTex, cls);
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
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\"><link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin><link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Bangers&family=Noto+Sans:wght@400;600;700;800;900&display=swap\">\n"
                + "<link rel=\"stylesheet\" href=\"" + r + "style.css\">\n</head>\n<body>\n"
                + "<header class=\"top\"><div class=\"wrap\"><a class=\"brand\" href=\"" + r + "index.html\">My Hero Craft <span>Wiki</span></a>\n"
                + "<nav><a href=\"" + r + "quirks/index.html\">Quirks</a><a href=\"" + r + "characters.html\">Characters</a><a href=\"" + r + "shops.html\">Shops</a><a href=\"" + r + "items.html\">Items</a><a href=\"" + r + "smartphone.html\">Phone</a><a href=\"" + r + "guide.html\">Guide</a></nav></div></header>\n"
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
                + card("shops.html", "Shops", "Mei Hatsume's Support Workshop and Lunch Rush's Cafeteria: what they sell and the prices.")
                + card("items.html", "Items", "Support items and every costume you can have made.")
                + card("smartphone.html", "Smartphone", "Hero agencies, villain groups, allies, apps and HeroTube moves.")
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

    /** 陣営ごとの色(CSS のクラス)。 */
    static String side(String group) {
        return group.startsWith("Villain") ? "villain" : group.startsWith("U.A.") ? "student" : "hero";
    }

    static String charactersPage(List<Npc> npcs) {
        StringBuilder b = new StringBuilder("<h1 class=\"title\">Characters</h1><p class=\"lead\">Heroes, U.A. students and villains that appear in My Hero Craft. "
                + "Pick a character to see their quirk and stats.</p>");
        String grp = null;
        for (Npc n : npcs) {
            if (!n.group().equals(grp)) {
                if (grp != null) b.append("</div>");
                grp = n.group();
                b.append("<h2 class=\"band ").append(side(grp)).append("\">").append(esc(grp)).append("</h2><div class=\"names\">");
            }
            b.append("<a class=\"").append(side(n.group())).append("\" href=\"characters/").append(n.id()).append(".html\">").append(esc(label(n))).append("</a>");
        }
        if (grp != null) b.append("</div>");
        b.append("<h2 class=\"band hero\">Shopkeepers</h2><div class=\"names\"><a class=\"student\" href=\"shops.html#hatsume\">Mei Hatsume</a><a class=\"hero\" href=\"shops.html#lunchrush\">Lunch Rush</a></div>");
        return layout("Characters", "Heroes, U.A. students and villains in the My Hero Craft Minecraft mod.", "characters.html", b.toString(), 0);
    }

    static String charPage(Npc n, Map<String, Tree> trees) {
        String sd = side(n.group());
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"crumb\"><a href=\"../characters.html\">Characters</a> / ").append(esc(n.group())).append("</p>");
        String quirks = n.quirks().stream().map(q -> q.equals("random") ? "Random" : trees.containsKey(q)
                ? "<a href=\"../quirks/" + q + ".html\">" + esc(kosei(q).name()) + "</a>" : esc(kosei(q).name())).collect(Collectors.joining(" / "));
        // 名前の帯(ヒーローカード風)
        b.append("<section class=\"profile ").append(sd).append("\"><span class=\"badge\">").append(esc(n.group())).append("</span>")
                .append("<h1>").append(esc(label(n))).append("</h1>")
                .append("<p class=\"quirk\"><span>QUIRK</span>").append(quirks).append("</p>");
        if (n.stats() != null) {
            int total = Arrays.stream(n.stats()).sum();
            b.append("<div class=\"score\"><b>").append(total).append("</b><span>TOTAL</span></div>");
        }
        b.append("</section>");
        String[] labels = {"Health", "Strength", "Dexterity", "Defense", "Quirk"};
        if (n.stats() != null) {
            b.append("<div class=\"statgrid\"><div class=\"panel\"><h2>Stats</h2>").append(radar(n.stats(), labels)).append("</div>");
            b.append("<div class=\"panel\"><h2>Details</h2><div class=\"bars\">");
            for (int i = 0; i < 5; i++) {
                int v = n.stats()[i];
                b.append("<div class=\"bar\"><span>").append(labels[i]).append("</span><div><i style=\"width:").append(Math.min(100, v * 5)).append("%\"></i></div><b>").append(v).append("</b></div>");
            }
            b.append("</div><p class=\"note\">Each stat goes up to 20.</p><div class=\"reward\"><b>")
                    .append(Math.max(1, Math.round(Arrays.stream(n.stats()).sum() / 4.0)))
                    .append("</b><span>skill points for defeating this character alone</span></div></div></div>");
        } else if (n.quirks().contains("random")) {
            b.append("<div class=\"panel\"><h2>Stats</h2><p>Each stat is random from 0 to 4, and the quirk is random too.</p>"
                    + "<div class=\"reward\"><b>2–5</b><span>skill points for defeating one alone (about 2–3 on average)</span></div></div>");
        } else {
            b.append("<div class=\"panel\"><h2>Stats</h2><p>Quirk level ").append(n.level()).append(".</p></div>");
        }
        return layout(label(n) + " (Character)", n.name() + " in the My Hero Craft Minecraft mod: quirk and stats.",
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

    static String itemName(String id) {
        String n = noFormat(lang("item.mhc." + id));
        return n.isEmpty() ? titleCase(id) : n;
    }

    static String slotName(String s) {
        return switch (s) { case "HELMET" -> "Head"; case "CHESTPLATE" -> "Body"; case "LEGGINGS" -> "Legs"; default -> "Feet"; };
    }

    static String priceTable(Map<String, Integer> slotCost) {
        StringBuilder b = new StringBuilder("<div class=\"table\"><table><thead><tr><th>Piece</th><th>Emeralds</th></tr></thead><tbody>");
        for (var e : slotCost.entrySet()) b.append("<tr><td>").append(slotName(e.getKey())).append("</td><td><b>").append(e.getValue()).append("</b></td></tr>");
        return b.append("</tbody></table></div>").toString();
    }

    static String supportTable(List<String[]> support) {
        StringBuilder b = new StringBuilder("<div class=\"table\"><table><thead><tr><th>Item</th><th>Emeralds</th></tr></thead><tbody>");
        for (String[] s : support) b.append("<tr><td><strong>").append(esc(itemName(s[0]))).append("</strong></td><td><b>").append(s[1]).append("</b></td></tr>");
        return b.append("</tbody></table></div>").toString();
    }

    static String shopsPage(List<String[]> support, Map<String, Integer> slotCost, int appCost, int tubeCost) {
        String phone = support.stream().filter(s -> s[0].equals("smartphone")).map(s -> s[1]).findFirst().orElse("?");
        StringBuilder b = new StringBuilder("<h1 class=\"title\">Shops</h1><p class=\"lead\">Two characters run shops in villages. Pay with emeralds.</p>");
        b.append("<section id=\"hatsume\" class=\"profile student\"><span class=\"badge\">Support Workshop</span><h1>Mei Hatsume</h1>")
                .append("<p class=\"quirk\"><span>WHERE</span>Villages</p></section>");
        b.append("<div class=\"statgrid\"><div class=\"panel\"><h2>How to use</h2><ul>")
                .append("<li>Mei Hatsume appears in villages and stays there even when you leave.</li>")
                .append("<li><b>Right-click</b> her to open <b>Hatsume's Support Workshop</b> (sneaking does a normal interaction).</li>")
                .append("<li>The workshop has two tabs: <b>Costumes</b> and <b>Support</b> items. Pay with emeralds from your inventory.</li>")
                .append("<li>Right-click her while holding a <a href=\"smartphone.html\">Smartphone</a> to open <b>Hatsume's App Store</b>.</li>")
                .append("</ul></div><div class=\"panel\"><h2>Costume prices</h2>").append(priceTable(slotCost))
                .append("<p class=\"note\">Every costume piece costs the same. See all costumes on the <a href=\"items.html#costumes\">Items</a> page.</p></div></div>");
        b.append("<div class=\"statgrid\"><div class=\"panel\"><h2>Support items</h2>").append(supportTable(support)).append("</div>")
                .append("<div class=\"panel\"><h2>App Store</h2><p>Install apps on the smartphone in your hand.</p>")
                .append("<div class=\"reward\"><b>").append(appCost).append("</b><span>emeralds per app (HeroTube: ").append(tubeCost).append(")</span></div>")
                .append("<p class=\"note\">In Creative mode, apps are free. The smartphone itself costs ").append(phone).append(" emeralds.</p></div></div>");
        b.append("<section id=\"lunchrush\" class=\"profile hero\" style=\"margin-top:36px\"><span class=\"badge\">Cafeteria</span><h1>Lunch Rush</h1>")
                .append("<p class=\"quirk\"><span>WHERE</span>Villages</p></section>");
        b.append("<div class=\"statgrid\"><div class=\"panel\"><h2>How to use</h2><ul>")
                .append("<li>Lunch Rush appears in villages and stays there even when you leave.</li>")
                .append("<li><b>Right-click</b> him to open <b>Lunch Rush's Cafeteria</b>.</li>")
                .append("<li>He can cook <b>any food</b>, including food from other mods. The menu has three tabs: U.A. Specials, Classics and Others.</li>")
                .append("<li>Choose how many you want. Hold <kbd>Shift</kbd> to change the amount by 8.</li>")
                .append("</ul></div><div class=\"panel\"><h2>Prices</h2>")
                .append("<div class=\"reward\"><b>1 : 1</b><span>one emerald for each hunger point the food restores</span></div>")
                .append("<ul><li>Golden Apple: <b>30</b> emeralds</li><li>Not on the menu: Food Set, Blood Collection Container, Enchanted Golden Apple</li></ul></div></div>");
        return layout("Shops", "Mei Hatsume's Support Workshop and Lunch Rush's Cafeteria in the My Hero Craft Minecraft mod: what they sell and for how many emeralds.", "shops.html", b.toString(), 0);
    }

    static String itemsPage(List<String[]> support, List<String[]> costumes, Map<String, Integer> slotCost) {
        StringBuilder b = new StringBuilder("<h1 class=\"title\">Items</h1><p class=\"lead\">Support items and costumes. All of them are made at <a href=\"shops.html#hatsume\">Mei Hatsume's Support Workshop</a> with emeralds.</p>");
        b.append("<h2 class=\"band hero\" id=\"support\">Support items</h2>").append(supportTable(support));
        b.append("<h2 class=\"band student\" id=\"costumes\">Costumes</h2><p class=\"note\">Prices per piece: ")
                .append(slotCost.entrySet().stream().map(e -> slotName(e.getKey()) + " " + e.getValue()).collect(Collectors.joining(" / "))).append(" emeralds.</p>");
        Map<String, List<String>> sets = new LinkedHashMap<>();
        for (String[] c : costumes) {
            String n = itemName(c[0]);
            int i = n.lastIndexOf('[');
            String set = (i > 0 ? n.substring(0, i) : n).trim();
            sets.computeIfAbsent(set, k -> new ArrayList<>()).add(slotName(c[1]));
        }
        b.append("<div class=\"grid\">");
        for (var e : sets.entrySet())
            b.append("<div class=\"item\"><strong>").append(esc(e.getKey())).append("</strong><span class=\"desc\">")
                    .append(String.join(" · ", e.getValue())).append("</span></div>");
        b.append("</div>");
        return layout("Items", "Support items and costumes in the My Hero Craft Minecraft mod, with their emerald prices.", "items.html", b.toString(), 0);
    }

    static String phonePage(List<String> apps, int appCost, int tubeCost, Map<String, List<String[]>> channels, List<String[]> support) {
        String phone = support.stream().filter(s -> s[0].equals("smartphone")).map(s -> s[1]).findFirst().orElse("?");
        StringBuilder b = new StringBuilder("<h1 class=\"title\">Smartphone</h1><p class=\"lead\">Run a hero agency or villain group, call your allies and learn new moves from HeroTube.</p>");
        b.append("<div class=\"statgrid\"><div class=\"panel\"><h2>Getting one</h2><div class=\"reward\"><b>").append(phone)
                .append("</b><span>emeralds at <a href=\"shops.html#hatsume\">Mei Hatsume's workshop</a> (Support tab)</span></div>")
                .append("<p><b>Right-click</b> with the smartphone to open it. The <b>Agency</b> app is installed from the start. Other apps are bought from Hatsume's App Store.</p></div>")
                .append("<div class=\"panel\"><h2>Agency / Villain group</h2><ul>")
                .append("<li>Create a <b>Hero Agency</b> (hero side) or a <b>Villain Group</b> (villain side) and give it a name.</li>")
                .append("<li>To invite a player, both of you hold a smartphone and you right-click them.</li>")
                .append("<li>To recruit a character as an ally, right-click an ally-side character that is under <b>10% health</b> with the smartphone.</li>")
                .append("<li>Allies can be told to <b>Follow</b> or <b>Stay</b>. Leaders can kick members or disband the group.</li></ul></div></div>");
        b.append("<h2 class=\"band hero\">Apps</h2><div class=\"table\"><table><thead><tr><th>App</th><th>What it does</th><th>Emeralds</th></tr></thead><tbody>")
                .append("<tr><td><strong>").append(esc(noFormat(lang("gui.mhc.phone.app.office")))).append("</strong></td><td>Create or manage your agency or group, members and allies.</td><td>Free</td></tr>");
        for (String a : apps)
            b.append("<tr><td><strong>").append(esc(noFormat(lang("gui.mhc.phone.app." + a)))).append("</strong></td><td>")
                    .append(esc(noFormat(lang("gui.mhc.phone.desc." + a)))).append("</td><td><b>").append(a.equals("herotube") ? tubeCost : appCost).append("</b></td></tr>");
        b.append("</tbody></table></div>");
        b.append("<h2 class=\"band student\" id=\"herotube\">HeroTube</h2><p>Watch videos to learn moves. Each video costs <b>skill points</b> (free in Creative mode). "
                + "Capture Scarf moves need Aizawa's costume (body) and syringe moves need Himiko Toga's costume Ver2 (body) to be used.</p>");
        for (var c : channels.entrySet()) {
            String cn = noFormat(lang("gui.mhc.herotube.channel." + c.getKey()));
            b.append("<h2>").append(esc(cn.isEmpty() ? titleCase(c.getKey()) : cn)).append("</h2><div class=\"table\"><table><thead><tr><th>Video</th><th>Move</th><th>Points</th></tr></thead><tbody>");
            for (String[] v : c.getValue()) {
                String title = noFormat(lang("gui.mhc.herotube.video." + v[0]));
                b.append("<tr><td>").append(esc(title.isEmpty() ? skillName(v[0]) : title)).append("</td><td>").append(esc(skillName(v[0])))
                        .append("</td><td><b>").append(v[1]).append("</b></td></tr>");
            }
            b.append("</tbody></table></div>");
        }
        return layout("Smartphone", "The smartphone in the My Hero Craft Minecraft mod: hero agencies, villain groups, allies, apps and HeroTube moves.", "smartphone.html", b.toString(), 0);
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

            function cubeMesh(c, mat, tw, th, sv, order) {
              const inf = c.inflate || 0, [w, h, d] = c.size;
              const geo = new THREE.BoxGeometry(Math.max(w + inf * 2, 0.001), Math.max(h + inf * 2, 0.001), Math.max(d + inf * 2, 0.001));
              const uv = geo.attributes.uv, rs = faceRects(c);
              const hidden = [];
              rs.forEach((r, i) => {
                if (!r) { hidden.push(i); return; }
                const [u0, v0, u1, v1] = r;
                uv.setXY(i * 4 + 0, u0 / tw, 1 - v0 / th * sv); uv.setXY(i * 4 + 1, u1 / tw, 1 - v0 / th * sv);
                uv.setXY(i * 4 + 2, u0 / tw, 1 - v1 / th * sv); uv.setXY(i * 4 + 3, u1 / tw, 1 - v1 / th * sv);
              });
              if (hidden.length) { const idx = geo.index.array; hidden.forEach(i => { for (let k = 0; k < 6; k++) idx[i * 6 + k] = 0; }); }
              const mesh = new THREE.Mesh(geo, mat); mesh.renderOrder = order; return mesh;
            }

            function build(json, tex, hide, order) {
              const g = (json['minecraft:geometry'] || [])[0];
              if (!g) return null;
              const tw = g.description.texture_width, th = g.description.texture_height;
              const img = tex.image, sv = Math.min(1, (img.width * th / tw) / img.height); // 縦に並んだ動くテクスチャは最初の1コマだけ使う
              const mat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, alphaTest: 0.02, side: THREE.DoubleSide });
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
                  const mesh = cubeMesh(c, mat, tw, th, sv, order);
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
                const m = build(json, tex, p.hide || [], parts.indexOf(p));
                if (m) scene.add(m);
              } catch (e) { console.warn('model part failed', p, e); }
            }
            // モデルの大きさに合わせてカメラの距離と注視点を決める(オールマイトなど大きいモデルも全身が入る)
            const box = new THREE.Box3().setFromObject(scene);
            if (!box.isEmpty()) {
              const c = box.getCenter(new THREE.Vector3()), s = box.getSize(new THREE.Vector3());
              const dist = Math.max(s.y, s.x * 1.4) * 0.62 / Math.tan(camera.fov * D / 2);
              controls.target.copy(c);
              camera.position.copy(c).add(new THREE.Vector3(-0.32, 0.08, -1).normalize().multiplyScalar(dist));
              controls.maxDistance = dist * 2.5; controls.minDistance = dist * 0.25;
            }
            function resize() { const w = el.clientWidth, h = el.clientHeight; renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix(); }
            new ResizeObserver(resize).observe(el); resize();
            renderer.setAnimationLoop(() => { controls.update(); renderer.render(scene, camera); });
            """;

    static final String CSS = """
            :root{--paper:#fbf7ec;--panel:#ffffff;--ink:#16181d;--muted:#5d6270;--line:#16181d;--soft:#e6e1d3;
              --hero:#1d9a5b;--gold:#ffcc1f;--red:#e0353b;--navy:#1b2a5c;--villain:#6b2d8f;--student:#1d9a5b;--pro:#e0a100}
            *{box-sizing:border-box}html{-webkit-text-size-adjust:100%}
            body{margin:0;color:var(--ink);font:16px/1.65 "Noto Sans",system-ui,-apple-system,"Segoe UI",sans-serif;
              background:var(--paper) radial-gradient(rgba(22,24,29,.07) 1px,transparent 1.2px) 0 0/14px 14px}
            a{color:var(--hero);font-weight:600}.wrap{max-width:1100px;margin:0 auto;padding:0 16px}
            .top{background:var(--navy);border-bottom:4px solid var(--gold);position:sticky;top:0;z-index:5}
            .top .wrap{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:60px;flex-wrap:wrap}
            .brand{display:inline-block;font-family:Bangers,Impact,sans-serif;letter-spacing:1.5px;font-size:28px;color:#fff;text-decoration:none;transform:skewX(-6deg)}
            .brand span{color:var(--gold)}
            nav a{margin-left:18px;text-decoration:none;color:#fff;font-weight:800;letter-spacing:.5px;text-transform:uppercase;font-size:14px}
            nav a:hover{color:var(--gold)}
            main{padding:24px 16px 56px}
            h1,.title{font-family:Bangers,Impact,sans-serif;font-weight:400;letter-spacing:1.5px;font-size:46px;line-height:1.05;margin:10px 0 10px;
              color:var(--ink);text-shadow:3px 3px 0 var(--gold)}
            h2{font-family:Bangers,Impact,sans-serif;font-weight:400;letter-spacing:1px;font-size:28px;margin:34px 0 12px}
            .lead{font-size:18px;color:var(--muted);max-width:780px}.users{color:var(--muted)}
            .hero{padding:28px 0 10px}.stats span{display:inline-block;margin:0 10px 8px 0;padding:4px 14px;border:2px solid var(--line);border-radius:999px;background:var(--gold);font-weight:800;box-shadow:3px 3px 0 var(--line)}
            .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:18px;margin-top:10px}
            .card{display:block;padding:20px;background:var(--panel);border:2px solid var(--line);border-radius:6px;text-decoration:none;color:var(--ink);box-shadow:5px 5px 0 var(--line);transition:transform .1s}
            .card:hover{transform:translate(-2px,-2px);box-shadow:7px 7px 0 var(--line)}
            .card h2{margin:0 0 6px;font-size:30px;color:var(--hero)}.card p{margin:0;color:var(--muted)}
            .search{width:100%;max-width:520px;padding:12px 16px;font-size:16px;border:2px solid var(--line);border-radius:6px;background:var(--panel);color:var(--ink);box-shadow:3px 3px 0 var(--line)}
            .cat{margin-top:30px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:14px}
            .item{display:flex;flex-direction:column;gap:2px;padding:14px;background:var(--panel);border:2px solid var(--line);border-radius:6px;text-decoration:none;color:var(--ink);box-shadow:3px 3px 0 var(--line)}
            .item:hover{background:#fff8d9}.item .users{font-size:13px}.item .desc{font-size:14px;color:var(--muted)}
            .crumb{color:var(--muted);margin:0;font-size:14px}
            .table{overflow-x:auto;border:2px solid var(--line);border-radius:6px;background:var(--panel);box-shadow:4px 4px 0 var(--line)}
            table{border-collapse:collapse;width:100%;font-size:15px}th,td{padding:10px 12px;border-bottom:1px solid var(--soft);text-align:left;vertical-align:top}
            th{background:var(--navy);color:#fff;font-size:13px;text-transform:uppercase;letter-spacing:.05em;white-space:nowrap}tr:last-child td{border-bottom:0}
            .tag{display:inline-block;font-size:12px;font-weight:800;padding:1px 8px;border:1.5px solid var(--line);border-radius:999px;margin-left:4px;vertical-align:middle}
            .goal{background:var(--gold)}.challenge{background:var(--red);color:#fff}
            .passives li{margin-bottom:6px}.note{color:var(--muted);font-size:14px}
            .tree,.tree ul{list-style:none;padding-left:20px;margin:0}.tree{padding-left:0}
            .tree li{position:relative;padding:4px 0 4px 16px;border-left:3px solid var(--hero)}
            .tree li>span{display:inline-block;padding:3px 12px;border:2px solid var(--line);border-radius:6px;background:var(--panel);font-weight:600;box-shadow:2px 2px 0 var(--line)}
            .inline{list-style:none;padding:0;display:flex;flex-wrap:wrap;gap:8px}.inline li a{display:inline-block;padding:4px 12px;border:2px solid var(--line);border-radius:999px;background:var(--panel);text-decoration:none;color:var(--ink)}
            .formula{padding:14px 18px;background:var(--gold);border:2px solid var(--line);border-radius:6px;font-weight:800;box-shadow:4px 4px 0 var(--line)}
            kbd,code{font-family:ui-monospace,Consolas,monospace;font-size:14px;background:#fff;border:1.5px solid var(--line);border-radius:4px;padding:1px 6px}
            footer{color:var(--muted);font-size:14px;padding:24px 16px 40px;border-top:2px solid var(--line)}
            .band{display:inline-block;padding:2px 16px;color:#fff;border:2px solid var(--line);transform:skewX(-8deg);box-shadow:4px 4px 0 var(--line)}
            .band.hero{background:var(--pro);color:var(--ink)}.band.student{background:var(--student)}.band.villain{background:var(--villain)}
            .names{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}
            .names a{display:block;padding:12px 14px;background:var(--panel);border:2px solid var(--line);border-left-width:8px;border-radius:6px;text-decoration:none;color:var(--ink);font-weight:700;box-shadow:3px 3px 0 var(--line)}
            .names a.hero{border-left-color:var(--pro)}.names a.student{border-left-color:var(--student)}.names a.villain{border-left-color:var(--villain)}
            .names a:hover{transform:translate(-1px,-1px);background:#fff8d9}
            .profile{position:relative;overflow:hidden;margin:10px 0 22px;padding:26px 28px;border:3px solid var(--line);border-radius:8px;color:#fff;box-shadow:7px 7px 0 var(--line)}
            .profile::after{content:"";position:absolute;inset:0;background:radial-gradient(rgba(255,255,255,.18) 1.4px,transparent 1.6px) 0 0/12px 12px;pointer-events:none}
            .profile.hero{background:linear-gradient(115deg,var(--navy) 0 55%,var(--pro) 55% 100%)}
            .profile.student{background:linear-gradient(115deg,var(--navy) 0 55%,var(--student) 55% 100%)}
            .profile.villain{background:linear-gradient(115deg,#1b1424 0 55%,var(--villain) 55% 100%)}
            .profile h1{color:#fff;text-shadow:4px 4px 0 var(--line);font-size:56px;margin:6px 0}
            .profile .badge{display:inline-block;background:var(--gold);color:var(--ink);font-weight:900;font-size:13px;letter-spacing:.08em;text-transform:uppercase;padding:2px 10px;border:2px solid var(--line);transform:skewX(-8deg)}
            .profile .quirk{margin:0;font-size:18px;font-weight:700}.profile .quirk span{font-size:12px;letter-spacing:.12em;margin-right:8px;opacity:.85}
            .profile .quirk a{color:var(--gold)}
            .profile .score{position:absolute;right:24px;top:50%;transform:translateY(-50%);text-align:center;z-index:1}
            .profile .score b{display:block;font-family:Bangers,Impact,sans-serif;font-weight:400;font-size:64px;line-height:1;color:var(--gold);text-shadow:3px 3px 0 var(--line)}
            .profile .score span{font-size:12px;letter-spacing:.15em;font-weight:800}
            .profile>*{position:relative;z-index:1}
            .statgrid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:18px;align-items:start}
            .panel{background:var(--panel);border:2px solid var(--line);border-radius:8px;padding:18px;box-shadow:5px 5px 0 var(--line)}.panel h2{margin:0 0 8px;font-size:26px}
            .radar{width:100%;max-width:360px;display:block;margin:0 auto}.radar .ring{fill:none;stroke:var(--soft);stroke-width:1.5}.radar .axis{stroke:var(--soft)}
            .radar .val{fill:rgba(255,204,31,.55);stroke:var(--ink);stroke-width:2.5;stroke-linejoin:round}.radar text{fill:var(--ink);font-size:12px;font-weight:700}
            .bars{display:grid;gap:10px;margin-top:6px}.bar{display:grid;grid-template-columns:86px 1fr 30px;align-items:center;gap:10px;font-weight:700}
            .bar div{height:14px;border:2px solid var(--line);border-radius:4px;background:#f1ede0;overflow:hidden}.bar i{display:block;height:100%;background:repeating-linear-gradient(135deg,var(--hero) 0 8px,#22b56b 8px 16px)}.bar b{text-align:right;font-size:18px}
            .reward{display:flex;align-items:center;gap:14px;margin-top:14px;padding:12px 14px;background:var(--gold);border:2px solid var(--line);border-radius:6px}
            .reward b{font-family:Bangers,Impact,sans-serif;font-weight:400;font-size:40px;line-height:1}.reward span{font-weight:700}
            @media (max-width:760px){.statgrid{grid-template-columns:1fr}.names{grid-template-columns:repeat(2,minmax(0,1fr))}.profile .score{position:static;transform:none;text-align:left;margin-top:10px}.profile h1{font-size:44px}}
            @media (max-width:600px){h1,.title{font-size:36px}nav a{margin-left:12px}}
            """;
}
