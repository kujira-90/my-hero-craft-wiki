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
    static Map<String, String> en = new HashMap<>(), ja = new HashMap<>();
    static final String SITE = "My Hero Craft Wiki";
    /** 今作っている言語("en" か "ja") */
    static String L = "en";

    /** 今の言語の文 */
    static String T(String enText, String jaText) { return L.equals("ja") ? jaText : enText; }

    public static void main(String[] a) throws Exception {
        mod = Path.of(a[0]);
        out = Path.of(a[1]);
        base = a.length > 2 ? a[2].replaceAll("/+$", "") : "https://kujira-90.github.io/my-hero-craft-wiki";
        en = loadLang(mod.resolve("src/main/resources/assets/mhc/lang/en_us.json"));
        ja = loadLang(mod.resolve("src/main/resources/assets/mhc/lang/ja_jp.json"));
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

        // 英語はそのまま、日本語は ja/ の下に、同じページを作る
        Path root = out;
        List<String> urls = new ArrayList<>();
        int quirkCount = 0;
        for (String lg : List.of("en", "ja")) {
            L = lg;
            out = lg.equals("ja") ? root.resolve("ja") : root;
            String pre = lg.equals("ja") ? "ja/" : "";
            List<String[]> quirkRows = new ArrayList<>(); // id, name, category, users, desc
            for (var c : cats.entrySet()) {
                for (String q : c.getValue()) {
                    Tree t = trees.get(q);
                    if (t == null) continue;
                    Kosei k = kosei(q);
                    quirkRows.add(new String[]{q, k.name, c.getKey(), k.users, plain(lang("mhc.chat.message.note." + q + "_desc"))});
                    write("quirks/" + q + ".html", quirkPage(q, k, c.getKey(), t, cd, dispatched, npcs));
                    urls.add(pre + "quirks/" + q + ".html");
                }
            }
            quirkCount = quirkRows.size();
            write("quirks/index.html", quirkIndex(quirkRows));
            urls.add(pre + "quirks/");
            write("characters.html", charactersPage(npcs));
            urls.add(pre + "characters.html");
            for (Npc n : npcs) {
                write("characters/" + n.id() + ".html", charPage(n, trees, cd));
                urls.add(pre + "characters/" + n.id() + ".html");
            }
            write("guide.html", guidePage());
            urls.add(pre + "guide.html");
            write("shops.html", shopsPage(support, slotCost, appCost, tubeCost));
            urls.add(pre + "shops.html");
            write("items.html", itemsPage(support, costumes, slotCost));
            urls.add(pre + "items.html");
            write("smartphone.html", phonePage(apps, appCost, tubeCost, channels, support));
            urls.add(pre + "smartphone.html");
            write("index.html", homePage(quirkRows.size(), npcs.size()));
            urls.add(pre);
            write("style.css", CSS);
            write("search.js", JS);
        }
        L = "en";
        out = root;
        write("404.html", layout("Page not found", "The page you were looking for does not exist.", "",
                "<h1>Page not found</h1><p>Go back to the <a href=\"" + base + "/index.html\">home page</a>.</p>", 0));
        write(".nojekyll", "");
        write("robots.txt", "User-agent: *\nAllow: /\nSitemap: " + base + "/sitemap.xml\n");
        StringBuilder sm = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (String u : urls) sm.append("  <url><loc>").append(base).append("/").append(u).append("</loc></url>\n");
        write("sitemap.xml", sm.append("</urlset>\n").toString());
        System.out.println("quirks=" + quirkCount + " characters=" + npcs.size() + " pages=" + (urls.size() + 1));
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

    /** 今の言語の翻訳(日本語に無ければ英語) */
    static String lang(String k) {
        String v = L.equals("ja") ? ja.get(k) : null;
        return v != null && !v.isEmpty() ? v : en.getOrDefault(k, "");
    }

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
        Matcher m = Pattern.compile("^(.*?)\\s*[\\[(](?:Users?|Main users?|主な使用者)\\s*[:：]\\s*(.*)[\\])]\\s*$", Pattern.CASE_INSENSITIVE).matcher(raw);
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
                k = k.equals("STR") ? T("Strength", "筋力") : k.equals("QUIRK") ? T("Quirk", "個性") : k.equals("ND") ? T("Needs", "必要") : titleCase(k.toLowerCase());
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
            if (toggleQuirk.contains(q)) return T("On toggle-off", "オフにした時");
            Map<String, String> sub = quirkSub.get(q);
            if (sub != null && sub.containsKey(now)) return seconds(sub.get(now));
            if (quirkDefault.containsKey(q)) return seconds(quirkDefault.get(q));
            return T("None", "なし");
        }

        static String seconds(String v) {
            v = v.replaceAll("f$", "").trim();
            if (v.matches("[0-9.]+")) {
                double d = Double.parseDouble(v);
                if (d == 0) return T("None", "なし");
                return d == Math.floor(d) ? String.valueOf((int) d) : String.valueOf(d);
            }
            Matcher m = Pattern.compile("\\?\\s*([0-9.]+)f?\\s*:\\s*0").matcher(v);
            if (m.find()) return seconds(m.group(1)) + T(" (when turned off)", "（オフにした時）");
            m = Pattern.compile("\\?\\s*0\\s*:\\s*([0-9.]+)f?").matcher(v);
            if (m.find()) return seconds(m.group(1));
            return T("varies", "変わる");
        }
    }

    // ------------------------------------------------------------------ NPC

    /** 3Dで描く部品: モデル(geo)・テクスチャ・隠す骨(防具で着ていない部位)。 */
    record Part(String geo, String tex, List<String> hide) {}

    record Npc(String id, String name, String group, int[] stats, List<String> quirks, int level, List<Part> parts, List<String> skills) {}

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
            // 使う技: SkillMath.setUseSkill(this,"技",true)(自分のクラスと親のクラス)
            Set<String> skills = new LinkedHashSet<>();
            for (String c : ancestors(cls)) {
                if (!classFile.containsKey(c)) break;
                Matcher us = Pattern.compile("setUseSkill\\(this\\s*,\\s*\"([a-z0-9_]+)\"\\s*,\\s*true\\s*\\)").matcher(strip(read(classFile.get(c))));
                while (us.find()) skills.add(us.group(1));
            }
            List<String> sk = new ArrayList<>(skills);
            Matcher s =Pattern.compile("SetEntityStats\\(this\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)((?:\\s*,\\s*\"[a-z0-9_]+\")*)\\s*\\)").matcher(src);
            Matcher l = Pattern.compile("SetEntityStats\\(this\\s*,\\s*\"([a-z0-9_]+)\"\\s*,\\s*(\\d+)\\s*\\)").matcher(src);
            if (s.find()) {
                List<String> q = new ArrayList<>();
                Matcher n = Pattern.compile("\"([a-z0-9_]+)\"").matcher(s.group(6));
                while (n.find()) q.add(n.group(1));
                list.add(new Npc(id, name, group, new int[]{num(s.group(1)), num(s.group(2)), num(s.group(3)), num(s.group(4)), num(s.group(5))}, q, 0, parts, sk));
            } else if (l.find()) {
                list.add(new Npc(id, name, group, null, List.of(l.group(1)), num(l.group(2)), parts, sk));
            } else if (cls.equals("Villan_Mob")) {
                list.add(new Npc(id, name, group, null, List.of("random"), 0, parts, sk));
            }
        }
        list.sort(Comparator.comparing(Npc::group).thenComparing(Npc::name));
        return list;
    }

    // ------------------------------------------------------------------ ページ

    static String rel(int depth) { return depth == 0 ? "" : "../".repeat(depth); }

    /** 言語の中のパス("" や "quirks/" は index.html を足したリンク先にする) */
    static String file(String path) { return path.isEmpty() || path.endsWith("/") ? path + "index.html" : path; }

    /** 一覧の分け方(英語の名前)を、今の言語で出す */
    static String group(String g) {
        return switch (g) {
            case "U.A. Students" -> T(g, "雄英生");
            case "Heroes" -> T(g, "ヒーロー");
            case "Vigilantes" -> T(g, "ヴィジランテ");
            case "Villains" -> T(g, "ヴィラン");
            case "Other Quirks" -> T(g, "その他の個性");
            default -> g;
        };
    }

    static String layout(String title, String desc, String canonical, String main, int depth) {
        String r = rel(depth);
        String full = title.equals(SITE) ? SITE : title + " - " + SITE;
        String enUrl = base + "/" + canonical, jaUrl = base + "/ja/" + canonical;
        String self = L.equals("ja") ? jaUrl : enUrl;
        // もう片方の言語の同じページ(英語は ja/ の下、日本語は1つ上)
        String other = L.equals("ja") ? r + "../" + file(canonical) : r + "ja/" + file(canonical);
        String otherLabel = L.equals("ja") ? "English" : "日本語";
        return "<!doctype html>\n<html lang=\"" + L + "\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + esc(full) + "</title>\n"
                + "<meta name=\"description\" content=\"" + esc(desc) + "\">\n"
                + "<link rel=\"canonical\" href=\"" + self + "\">\n"
                + "<link rel=\"alternate\" hreflang=\"en\" href=\"" + enUrl + "\">\n"
                + "<link rel=\"alternate\" hreflang=\"ja\" href=\"" + jaUrl + "\">\n"
                + "<link rel=\"alternate\" hreflang=\"x-default\" href=\"" + enUrl + "\">\n"
                + "<meta property=\"og:title\" content=\"" + esc(full) + "\">\n"
                + "<meta property=\"og:description\" content=\"" + esc(desc) + "\">\n"
                + "<meta property=\"og:type\" content=\"website\">\n"
                + "<meta property=\"og:url\" content=\"" + self + "\">\n"
                + "<meta property=\"og:locale\" content=\"" + (L.equals("ja") ? "ja_JP" : "en_US") + "\">\n"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\"><link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin><link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Bangers&family=Noto+Sans:wght@400;600;700;800;900&family=Noto+Sans+JP:wght@400;700;900&display=swap\">\n"
                + "<link rel=\"stylesheet\" href=\"" + r + "style.css\">\n</head>\n<body>\n"
                + "<header class=\"top\"><div class=\"wrap\"><a class=\"brand\" href=\"" + r + "index.html\">My Hero Craft <span>Wiki</span></a>\n"
                + "<nav><a href=\"" + r + "quirks/index.html\">" + T("Quirks", "個性") + "</a><a href=\"" + r + "characters.html\">" + T("Characters", "キャラ")
                + "</a><a href=\"" + r + "shops.html\">" + T("Shops", "お店") + "</a><a href=\"" + r + "items.html\">" + T("Items", "アイテム")
                + "</a><a href=\"" + r + "smartphone.html\">" + T("Phone", "スマホ") + "</a><a href=\"" + r + "guide.html\">" + T("Guide", "ガイド")
                + "</a><a class=\"lang\" href=\"" + other + "\" hreflang=\"" + (L.equals("ja") ? "en" : "ja") + "\">" + otherLabel + "</a></nav></div></header>\n"
                + "<main class=\"wrap\">\n" + main + "\n</main>\n"
                + "<footer class=\"wrap\"><p>" + T("Unofficial fan wiki for the Minecraft mod <strong>My Hero Craft [MHC]</strong> (Forge 1.20.1) by kujira. "
                + "Generated from the mod's data. Not affiliated with My Hero Academia, Mojang or Microsoft.",
                "マインクラフトの MOD <strong>My Hero Craft [MHC]</strong>（Forge 1.20.1・作者 kujira）の非公式ファン Wiki です。"
                + "MOD のデータから自動で作っています。僕のヒーローアカデミア・Mojang・Microsoft とは関係ありません。") + "</p></footer>\n"
                + "</body>\n</html>\n";
    }

    static String homePage(int quirks, int chars) {
        String main = "<section class=\"hero\"><h1>My Hero Craft Wiki</h1>"
                + "<p class=\"lead\">" + T("The guide to <strong>My Hero Craft [MHC]</strong>, a Minecraft Forge 1.20.1 mod that brings the quirks, heroes and villains of My Hero Academia into Minecraft.",
                "<strong>My Hero Craft [MHC]</strong> は、僕のヒーローアカデミアの個性・ヒーロー・ヴィランをマインクラフトに持ち込む Forge 1.20.1 の MOD です。その遊び方をまとめた Wiki です。") + "</p>"
                + "<p class=\"stats\"><span>" + quirks + T(" quirks", " 個性") + "</span><span>" + chars + T(" characters", " キャラ") + "</span></p></section>"
                + "<div class=\"cards\">"
                + card("quirks/index.html", T("Quirks", "個性"), T("Every quirk you can get, with its skills, attack power, cooldowns and skill tree.", "手に入る全部の個性と、その技・攻撃力・クールダウン・スキルツリー。"))
                + card("characters.html", T("Characters", "キャラクター"), T("Heroes, U.A. students and villains that appear in the world, with their stats and quirks.", "ワールドに出てくるヒーロー・雄英生・ヴィランの個性とステータス。"))
                + card("shops.html", T("Shops", "お店"), T("Mei Hatsume's Support Workshop and Lunch Rush's Cafeteria: what they sell and the prices.", "発目明のサポート工房と、ランチラッシュの食堂。売っている物と値段。"))
                + card("items.html", T("Items", "アイテム"), T("Support items and every costume you can have made.", "サポートアイテムと、作ってもらえる全部のコスチューム。"))
                + card("smartphone.html", T("Smartphone", "スマホ"), T("Hero agencies, villain groups, allies, apps and HeroTube moves.", "ヒーロー事務所・ヴィラングループ・仲間・アプリ・HeroTube の技。"))
                + card("guide.html", T("Guide", "ガイド"), T("Controls, Hero Mode, skill points, the skill tree and the config file.", "操作・ヒーローモード・スキルポイント・スキルツリー・設定ファイル。"))
                + "</div>";
        return layout(SITE, T("Unofficial wiki for My Hero Craft [MHC], a Minecraft Forge 1.20.1 My Hero Academia mod: every quirk, skill, skill tree, character and control.",
                "僕のヒーローアカデミアのマイクラ MOD「My Hero Craft [MHC]」(Forge 1.20.1) の非公式 Wiki。全部の個性・技・スキルツリー・キャラ・操作をまとめています。"), "", main, 0);
    }

    static String card(String href, String t, String d) {
        return "<a class=\"card\" href=\"" + href + "\"><h2>" + t + "</h2><p>" + esc(d) + "</p></a>";
    }

    static String quirkIndex(List<String[]> rows) {
        StringBuilder b = new StringBuilder("<h1>" + T("Quirks", "個性") + "</h1><p class=\"lead\">"
                + T("All " + rows.size() + " quirks in My Hero Craft. Type to search by quirk or user.", "My Hero Craft の全 " + rows.size() + " 個性です。個性の名前や使う人の名前で探せます。") + "</p>"
                + "<input id=\"q\" class=\"search\" type=\"search\" placeholder=\"" + T("Search quirks (e.g. Explosion, Bakugo)", "個性を探す（例：爆破、爆豪）")
                + "\" aria-label=\"" + T("Search quirks", "個性を探す") + "\">");
        String cat = null;
        for (String[] r : rows) {
            if (!r[2].equals(cat)) {
                if (cat != null) b.append("</div>");
                cat = r[2];
                b.append("<h2 class=\"cat\">").append(esc(group(cat))).append("</h2><div class=\"grid\">");
            }
            b.append("<a class=\"item\" href=\"").append(r[0]).append(".html\" data-s=\"").append(esc((r[1] + " " + r[3] + " " + r[0]).toLowerCase())).append("\">")
                    .append("<strong>").append(esc(r[1])).append("</strong>")
                    .append(r[3].isEmpty() ? "" : "<span class=\"users\">" + esc(r[3]) + "</span>")
                    .append(r[4].isEmpty() ? "" : "<span class=\"desc\">" + esc(r[4]) + "</span>")
                    .append("</a>");
        }
        if (cat != null) b.append("</div>");
        b.append("<script src=\"../search.js\"></script>");
        return layout(T("Quirks", "個性一覧"), T("List of every quirk in the My Hero Craft Minecraft mod, grouped into U.A. students, heroes, villains, vigilantes and others.",
                "マイクラ MOD「My Hero Craft」の全部の個性の一覧。雄英生・ヒーロー・ヴィラン・ヴィジランテ・その他に分けています。"), "quirks/", b.toString(), 1);
    }

    static String quirkPage(String q, Kosei k, String cat, Tree t, Cd cd, Set<String> dispatched, List<Npc> npcs) {
        String desc = plain(lang("mhc.chat.message.note." + q + "_desc"));
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"crumb\"><a href=\"index.html\">").append(T("Quirks", "個性")).append("</a> / ").append(esc(group(cat))).append("</p>");
        b.append("<h1>").append(esc(k.name)).append("</h1>");
        if (!k.users.isEmpty()) b.append("<p class=\"users\">").append(T("Used by: ", "主な使用者：")).append(esc(k.users)).append("</p>");
        if (!desc.isEmpty()) b.append("<p class=\"lead\">").append(esc(desc)).append("</p>");

        List<Node> passives = t.nodes.stream().filter(n -> n.passive).toList();
        List<Node> skills = t.nodes.stream().filter(n -> !n.passive).toList();
        if (!passives.isEmpty()) {
            b.append("<h2>").append(T("Passives", "パッシブ")).append("</h2><ul class=\"passives\">");
            for (Node n : passives) {
                String[] p = splitNote(lang("mhc.chat.message.note." + n.id));
                String title = p != null ? noFormat(p[0]) : titleCase(n.id);
                b.append("<li><strong>").append(esc(title)).append("</strong> ").append(esc(plain(lang("mhc.chat.message.note." + n.id)))).append("</li>");
            }
            b.append("</ul>");
        }
        if (!skills.isEmpty()) {
            b.append("<h2>").append(T("Skills", "技")).append("</h2><div class=\"table\"><table><thead><tr><th>").append(T("Skill", "技")).append("</th><th>")
                    .append(T("Description", "説明")).append("</th><th>").append(T("Attack", "攻撃力")).append("</th><th>").append(T("Cooldown (s)", "CD（秒）"))
                    .append("</th><th>").append(T("Points", "ポイント")).append("</th><th>").append(T("Unlocked after", "前提の技")).append("</th></tr></thead><tbody>");
            for (Node n : skills) {
                String note = lang("mhc.chat.message.note." + n.id);
                String name = skillName(n.id);
                String pre = n.pre.stream().map(p -> p == t.root ? "—" : skillName(p.id)).collect(Collectors.joining(", "));
                String frame = n.frame.equals("GOAL") ? " <span class=\"tag goal\">" + T("Goal", "ゴール") + "</span>"
                        : n.frame.equals("CHALLENGE") ? " <span class=\"tag challenge\">" + T("Challenge", "チャレンジ") + "</span>" : "";
                b.append("<tr><td><strong>").append(esc(name)).append("</strong>").append(frame).append("</td><td>").append(esc(plain(note)))
                        .append("</td><td>").append(esc(atk(note))).append("</td><td>").append(esc(cd.of(n.id))).append("</td><td>")
                        .append(n.cost).append("</td><td>").append(esc(pre.isEmpty() ? "—" : pre)).append("</td></tr>");
            }
            b.append("</tbody></table></div>");
            b.append("<h2>").append(T("Skill tree", "スキルツリー")).append("</h2><p class=\"note\">")
                    .append(T("Skills unlock in this order. Spend skill points in the skill tree screen (Quirk Note) to learn them.",
                            "この順番で技を覚えられます。スキルツリーの画面（個性ノート）でスキルポイントを使って覚えます。")).append("</p>");
            b.append("<ul class=\"tree\">");
            for (Node c : t.root.children) if (!c.passive) treeItem(b, c, new HashSet<>());
            b.append("</ul>");
        }
        List<Npc> users = npcs.stream().filter(n -> n.quirks().contains(q)).toList();
        if (!users.isEmpty()) {
            b.append("<h2>").append(T("Characters with this quirk", "この個性を持つキャラ")).append("</h2><ul class=\"inline\">");
            for (Npc n : users) b.append("<li><a href=\"../characters/").append(n.id()).append(".html\">").append(esc(label(n))).append("</a></li>");
            b.append("</ul>");
        }
        String meta = L.equals("ja")
                ? k.name + "は、マイクラ MOD「My Hero Craft」の個性です" + (k.users.isEmpty() ? "" : "（主な使用者：" + k.users + "）") + "。" + desc + "技・攻撃力・クールダウン・スキルツリーをまとめています。"
                : k.name + " is a quirk in the My Hero Craft Minecraft mod" + (k.users.isEmpty() ? "" : " (used by " + k.users + ")") + ". "
                + (desc.isEmpty() ? "" : desc + " ") + "Skills, attack power, cooldowns and skill tree.";
        return layout(k.name + T(" (Quirk)", "（個性）"), meta.length() > 300 ? meta.substring(0, 297) + "..." : meta, "quirks/" + q + ".html", b.toString(), 1);
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
        if (!q.isEmpty()) return q.replace("%s", T("Target", "相手"));
        String[] p = splitNote(lang("mhc.chat.message.note." + id));
        return p != null ? noFormat(p[0]) : titleCase(id);
    }

    /** 同じ名前の別バージョン(student_midoriya_2 など)は番号を付けて見分ける。 */
    static String label(Npc n) {
        Matcher m = Pattern.compile("_(\\d+)$").matcher(n.id());
        String name = noFormat(lang("entity.mhc." + n.id()));
        if (name.isEmpty()) name = n.name();
        return m.find() ? name + " (" + m.group(1) + ")" : name;
    }

    /** 陣営ごとの色(CSS のクラス)。 */
    static String side(String group) {
        return group.startsWith("Villain") ? "villain" : group.startsWith("U.A.") ? "student" : "hero";
    }

    static String charactersPage(List<Npc> npcs) {
        StringBuilder b = new StringBuilder("<h1 class=\"title\">" + T("Characters", "キャラクター") + "</h1><p class=\"lead\">"
                + T("Heroes, U.A. students and villains that appear in My Hero Craft. Pick a character to see their quirk and stats.",
                "My Hero Craft に出てくるヒーロー・雄英生・ヴィランです。キャラを選ぶと、個性とステータスが見られます。") + "</p>");
        String grp = null;
        for (Npc n : npcs) {
            if (!n.group().equals(grp)) {
                if (grp != null) b.append("</div>");
                grp = n.group();
                b.append("<h2 class=\"band ").append(side(grp)).append("\">").append(esc(group(grp))).append("</h2><div class=\"names\">");
            }
            b.append("<a class=\"").append(side(n.group())).append("\" href=\"characters/").append(n.id()).append(".html\">").append(esc(label(n))).append("</a>");
        }
        if (grp != null) b.append("</div>");
        b.append("<h2 class=\"band hero\">").append(T("Shopkeepers", "お店の人")).append("</h2><div class=\"names\"><a class=\"student\" href=\"shops.html#hatsume\">")
                .append(T("Mei Hatsume", "発目明")).append("</a><a class=\"hero\" href=\"shops.html#lunchrush\">").append(T("Lunch Rush", "ランチラッシュ")).append("</a></div>");
        return layout(T("Characters", "キャラクター"), T("Heroes, U.A. students and villains in the My Hero Craft Minecraft mod.",
                "マイクラ MOD「My Hero Craft」に出てくるヒーロー・雄英生・ヴィランの一覧。"), "characters.html", b.toString(), 0);
    }

    static String charPage(Npc n, Map<String, Tree> trees, Cd cd) {
        String sd = side(n.group());
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"crumb\"><a href=\"../characters.html\">").append(T("Characters", "キャラクター")).append("</a> / ").append(esc(group(n.group()))).append("</p>");
        String quirks = n.quirks().stream().map(q -> q.equals("random") ? T("Random", "ランダム") : trees.containsKey(q)
                ? "<a href=\"../quirks/" + q + ".html\">" + esc(kosei(q).name()) + "</a>" : esc(kosei(q).name())).collect(Collectors.joining(" / "));
        // 名前の帯(ヒーローカード風)
        b.append("<section class=\"profile ").append(sd).append("\"><span class=\"badge\">").append(esc(group(n.group()))).append("</span>")
                .append("<h1>").append(esc(label(n))).append("</h1>")
                .append("<p class=\"quirk\"><span>").append(T("QUIRK", "個性")).append("</span>").append(quirks).append("</p>");
        if (n.stats() != null) {
            int total = Arrays.stream(n.stats()).sum();
            b.append("<div class=\"score\"><b>").append(total).append("</b><span>").append(T("TOTAL", "合計")).append("</span></div>");
        }
        b.append("</section>");
        String[] labels = L.equals("ja") ? new String[]{"体力", "筋力", "器用さ", "防御", "個性"} : new String[]{"Health", "Strength", "Dexterity", "Defense", "Quirk"};
        if (n.stats() != null) {
            b.append("<div class=\"statgrid\"><div class=\"panel\"><h2>").append(T("Stats", "ステータス")).append("</h2>").append(radar(n.stats(), labels)).append("</div>");
            b.append("<div class=\"panel\"><h2>").append(T("Details", "くわしく")).append("</h2><div class=\"bars\">");
            for (int i = 0; i < 5; i++) {
                int v = n.stats()[i];
                b.append("<div class=\"bar\"><span>").append(labels[i]).append("</span><div><i style=\"width:").append(Math.min(100, v * 5)).append("%\"></i></div><b>").append(v).append("</b></div>");
            }
            b.append("</div><p class=\"note\">").append(T("Each stat goes up to 20.", "どのステータスも最大は20です。")).append("</p><div class=\"reward\"><b>")
                    .append(Math.max(1, Math.round(Arrays.stream(n.stats()).sum() / 4.0)))
                    .append("</b><span>").append(T("skill points for defeating this character alone", "スキルポイント（1人で倒した時）")).append("</span></div></div></div>");
        } else if (n.quirks().contains("random")) {
            b.append("<div class=\"panel\"><h2>").append(T("Stats", "ステータス")).append("</h2><p>")
                    .append(T("Each stat is random from 0 to 4, and the quirk is random too.", "ステータスはそれぞれ0〜4のランダムで、個性もランダムです。")).append("</p>")
                    .append("<div class=\"reward\"><b>2–5</b><span>").append(T("skill points for defeating one alone (about 2–3 on average)", "スキルポイント（1人で倒した時。平均2〜3くらい）")).append("</span></div></div>");
        } else {
            b.append("<div class=\"panel\"><h2>").append(T("Stats", "ステータス")).append("</h2><p>").append(T("Quirk level ", "個性 Lv ")).append(n.level()).append(T(".", "")).append("</p></div>");
        }
        if (!n.skills().isEmpty()) {
            b.append("<h2>").append(T("Skills used", "使う技")).append("</h2><div class=\"table\"><table><thead><tr><th>").append(T("Skill", "技")).append("</th><th>")
                    .append(T("Description", "説明")).append("</th><th>").append(T("Attack", "攻撃力")).append("</th><th>").append(T("Cooldown (s)", "CD（秒）"))
                    .append("</th></tr></thead><tbody>");
            for (String s : n.skills()) {
                String q = trees.entrySet().stream().filter(e -> e.getValue().nodes.stream().anyMatch(x -> x.id.equals(s))).map(Map.Entry::getKey).findFirst().orElse(null);
                String note = lang("mhc.chat.message.note." + s);
                String name = esc(skillName(s));
                b.append("<tr><td><strong>").append(q != null ? "<a href=\"../quirks/" + q + ".html\">" + name + "</a>" : name).append("</strong></td><td>")
                        .append(esc(plain(note))).append("</td><td>").append(esc(atk(note))).append("</td><td>").append(esc(cd.of(s))).append("</td></tr>");
            }
            b.append("</tbody></table></div>");
        }
        return layout(label(n) + T(" (Character)", "（キャラクター）"), L.equals("ja") ? label(n) + "（マイクラ MOD「My Hero Craft」）の個性・ステータス・使う技。"
                : n.name() + " in the My Hero Craft Minecraft mod: quirk, stats and skills.", "characters/" + n.id() + ".html", b.toString(), 1);
    }

    /** 5つのステータスのレーダーチャート(SVG)。最大20。 */
    static String radar(int[] v, String[] labels) {
        double cx = 150, cy = 140, r = 100;
        StringBuilder s = new StringBuilder("<svg class=\"radar\" viewBox=\"0 0 300 290\" role=\"img\" aria-label=\"" + T("Stats chart", "ステータスのグラフ") + "\">");
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
        return switch (s) {
            case "HELMET" -> T("Head", "頭");
            case "CHESTPLATE" -> T("Body", "胴");
            case "LEGGINGS" -> T("Legs", "脚");
            default -> T("Feet", "足");
        };
    }

    static String priceTable(Map<String, Integer> slotCost) {
        StringBuilder b = new StringBuilder("<div class=\"table\"><table><thead><tr><th>" + T("Piece", "部位") + "</th><th>" + T("Emeralds", "エメラルド") + "</th></tr></thead><tbody>");
        for (var e : slotCost.entrySet()) b.append("<tr><td>").append(slotName(e.getKey())).append("</td><td><b>").append(e.getValue()).append("</b></td></tr>");
        return b.append("</tbody></table></div>").toString();
    }

    static String supportTable(List<String[]> support) {
        StringBuilder b = new StringBuilder("<div class=\"table\"><table><thead><tr><th>" + T("Item", "アイテム") + "</th><th>" + T("Emeralds", "エメラルド") + "</th></tr></thead><tbody>");
        for (String[] s : support) b.append("<tr><td><strong>").append(esc(itemName(s[0]))).append("</strong></td><td><b>").append(s[1]).append("</b></td></tr>");
        return b.append("</tbody></table></div>").toString();
    }

    static String li(String en, String ja) { return "<li>" + T(en, ja) + "</li>"; }

    static String shopsPage(List<String[]> support, Map<String, Integer> slotCost, int appCost, int tubeCost) {
        String phone = support.stream().filter(s -> s[0].equals("smartphone")).map(s -> s[1]).findFirst().orElse("?");
        StringBuilder b = new StringBuilder("<h1 class=\"title\">" + T("Shops", "お店") + "</h1><p class=\"lead\">"
                + T("Two characters run shops in villages. Pay with emeralds.", "村には、お店をやっているキャラが2人います。支払いはエメラルドです。") + "</p>");
        b.append("<section id=\"hatsume\" class=\"profile student\"><span class=\"badge\">").append(T("Support Workshop", "サポート工房")).append("</span><h1>")
                .append(T("Mei Hatsume", "発目明")).append("</h1><p class=\"quirk\"><span>").append(T("WHERE", "場所")).append("</span>").append(T("Villages", "村")).append("</p></section>");
        b.append("<div class=\"statgrid\"><div class=\"panel\"><h2>").append(T("How to use", "使い方")).append("</h2><ul>")
                .append(li("Mei Hatsume appears in villages and stays there even when you leave.", "発目明は村に出てきます。離れても消えません。"))
                .append(li("<b>Right-click</b> her to open <b>Hatsume's Support Workshop</b> (sneaking does a normal interaction).", "<b>右クリック</b>すると<b>発目のサポート工房</b>が開きます（しゃがんでいる時はふつうの右クリックです）。"))
                .append(li("The workshop has two tabs: <b>Costumes</b> and <b>Support</b> items. Pay with emeralds from your inventory.", "工房には<b>コスチューム</b>と<b>サポートアイテム</b>の2つのタブがあります。持っているエメラルドで払います。"))
                .append(li("Right-click her while holding a <a href=\"smartphone.html\">Smartphone</a> to open <b>Hatsume's App Store</b>.", "<a href=\"smartphone.html\">スマホ</a>を持って右クリックすると<b>発目のアプリストア</b>が開きます。"))
                .append("</ul></div><div class=\"panel\"><h2>").append(T("Costume prices", "コスチュームの値段")).append("</h2>").append(priceTable(slotCost))
                .append("<p class=\"note\">").append(T("Every costume piece costs the same. See all costumes on the <a href=\"items.html#costumes\">Items</a> page.",
                        "どのコスチュームも、部位ごとの値段は同じです。全部のコスチュームは<a href=\"items.html#costumes\">アイテム</a>のページにあります。")).append("</p></div></div>");
        b.append("<div class=\"statgrid\"><div class=\"panel\"><h2>").append(T("Support items", "サポートアイテム")).append("</h2>").append(supportTable(support)).append("</div>")
                .append("<div class=\"panel\"><h2>").append(T("App Store", "アプリストア")).append("</h2><p>").append(T("Install apps on the smartphone in your hand.", "手に持っているスマホにアプリを入れます。")).append("</p>")
                .append("<div class=\"reward\"><b>").append(appCost).append("</b><span>").append(T("emeralds per app (HeroTube: " + tubeCost + ")", "エメラルド（アプリ1つ。HeroTube は " + tubeCost + "）")).append("</span></div>")
                .append("<p class=\"note\">").append(T("In Creative mode, apps are free. The smartphone itself costs " + phone + " emeralds.",
                        "クリエイティブではアプリは無料です。スマホ本体は " + phone + " エメラルドです。")).append("</p></div></div>");
        b.append("<section id=\"lunchrush\" class=\"profile hero\" style=\"margin-top:36px\"><span class=\"badge\">").append(T("Cafeteria", "食堂")).append("</span><h1>")
                .append(T("Lunch Rush", "ランチラッシュ")).append("</h1><p class=\"quirk\"><span>").append(T("WHERE", "場所")).append("</span>").append(T("Villages", "村")).append("</p></section>");
        b.append("<div class=\"statgrid\"><div class=\"panel\"><h2>").append(T("How to use", "使い方")).append("</h2><ul>")
                .append(li("Lunch Rush appears in villages and stays there even when you leave.", "ランチラッシュは村に出てきます。離れても消えません。"))
                .append(li("<b>Right-click</b> him to open <b>Lunch Rush's Cafeteria</b>.", "<b>右クリック</b>すると<b>ランチラッシュの食堂</b>が開きます。"))
                .append(li("He can cook <b>any food</b>, including food from other mods. The menu has three tabs: U.A. Specials, Classics and Others.",
                        "<b>どんな食べ物でも</b>作ってくれます（ほかの MOD の食べ物も）。メニューは「雄英の定番」「いつもの」「そのほか」の3つのタブです。"))
                .append(li("Choose how many you want. Hold <kbd>Shift</kbd> to change the amount by 8.", "数を選んで注文します。<kbd>Shift</kbd> を押しながらだと8個ずつ変わります。"))
                .append("</ul></div><div class=\"panel\"><h2>").append(T("Prices", "値段")).append("</h2>")
                .append("<div class=\"reward\"><b>1 : 1</b><span>").append(T("one emerald for each hunger point the food restores", "回復する満腹度1につき、エメラルド1つ")).append("</span></div>")
                .append("<ul>").append(li("Golden Apple: <b>30</b> emeralds", "金のリンゴ：<b>30</b> エメラルド"))
                .append(li("Not on the menu: Food Set, Blood Collection Container, Enchanted Golden Apple", "作れない物：再現用食料セット、血液保管瓶、エンチャントされた金のリンゴ"))
                .append("</ul></div></div>");
        return layout(T("Shops", "お店"), T("Mei Hatsume's Support Workshop and Lunch Rush's Cafeteria in the My Hero Craft Minecraft mod: what they sell and for how many emeralds.",
                "マイクラ MOD「My Hero Craft」の発目明のサポート工房と、ランチラッシュの食堂。売っている物とエメラルドの値段。"), "shops.html", b.toString(), 0);
    }

    static String itemsPage(List<String[]> support, List<String[]> costumes, Map<String, Integer> slotCost) {
        StringBuilder b = new StringBuilder("<h1 class=\"title\">" + T("Items", "アイテム") + "</h1><p class=\"lead\">"
                + T("Support items and costumes. All of them are made at <a href=\"shops.html#hatsume\">Mei Hatsume's Support Workshop</a> with emeralds.",
                "サポートアイテムとコスチュームです。どれも<a href=\"shops.html#hatsume\">発目明のサポート工房</a>でエメラルドを払って作ってもらえます。") + "</p>");
        b.append("<h2 class=\"band hero\" id=\"support\">").append(T("Support items", "サポートアイテム")).append("</h2>").append(supportTable(support));
        b.append("<h2 class=\"band student\" id=\"costumes\">").append(T("Costumes", "コスチューム")).append("</h2><p class=\"note\">").append(T("Prices per piece: ", "部位ごとの値段："))
                .append(slotCost.entrySet().stream().map(e -> slotName(e.getKey()) + " " + e.getValue()).collect(Collectors.joining(" / "))).append(T(" emeralds.", " エメラルド")).append("</p>");
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
        return layout(T("Items", "アイテム"), T("Support items and costumes in the My Hero Craft Minecraft mod, with their emerald prices.",
                "マイクラ MOD「My Hero Craft」のサポートアイテムとコスチューム。エメラルドの値段つき。"), "items.html", b.toString(), 0);
    }

    static String phonePage(List<String> apps, int appCost, int tubeCost, Map<String, List<String[]>> channels, List<String[]> support) {
        String phone = support.stream().filter(s -> s[0].equals("smartphone")).map(s -> s[1]).findFirst().orElse("?");
        StringBuilder b = new StringBuilder("<h1 class=\"title\">" + T("Smartphone", "スマホ") + "</h1><p class=\"lead\">"
                + T("Run a hero agency or villain group, call your allies and learn new moves from HeroTube.", "ヒーロー事務所やヴィラングループを作ったり、仲間を呼んだり、HeroTube で新しい技を覚えたりできます。") + "</p>");
        b.append("<div class=\"statgrid\"><div class=\"panel\"><h2>").append(T("Getting one", "手に入れ方")).append("</h2><div class=\"reward\"><b>").append(phone)
                .append("</b><span>").append(T("emeralds at <a href=\"shops.html#hatsume\">Mei Hatsume's workshop</a> (Support tab)", "エメラルド（<a href=\"shops.html#hatsume\">発目の工房</a>のサポートアイテムのタブ）")).append("</span></div><p>")
                .append(T("<b>Right-click</b> with the smartphone to open it. The <b>Agency</b> app is installed from the start. Other apps are bought from Hatsume's App Store.",
                        "スマホを持って<b>右クリック</b>すると開きます。<b>事務所</b>のアプリは最初から入っています。ほかのアプリは発目のアプリストアで買います。")).append("</p></div>")
                .append("<div class=\"panel\"><h2>").append(T("Agency / Villain group", "事務所 / ヴィラングループ")).append("</h2><ul>")
                .append(li("Create a <b>Hero Agency</b> (hero side) or a <b>Villain Group</b> (villain side) and give it a name.", "<b>ヒーロー事務所</b>（ヒーロー側）か<b>ヴィラングループ</b>（ヴィラン側）を、名前を付けて作れます。"))
                .append(li("To invite a player, both of you hold a smartphone and you right-click them.", "プレイヤーを誘う時は、2人ともスマホを持って、相手を右クリックします。"))
                .append(li("To recruit a character as an ally, right-click an ally-side character that is under <b>10% health</b> with the smartphone.", "キャラを仲間にする時は、体力が <b>10% より少ない</b>味方側のキャラを、スマホで右クリックします。"))
                .append(li("Allies can be told to <b>Follow</b> or <b>Stay</b>. Leaders can kick members or disband the group.", "仲間には<b>ついてくる</b>か<b>待つ</b>かを指示できます。リーダーはメンバーを外したり、解散したりできます。"))
                .append("</ul></div></div>");
        b.append("<h2 class=\"band hero\">").append(T("Apps", "アプリ")).append("</h2><div class=\"table\"><table><thead><tr><th>").append(T("App", "アプリ")).append("</th><th>")
                .append(T("What it does", "できること")).append("</th><th>").append(T("Emeralds", "エメラルド")).append("</th></tr></thead><tbody>")
                .append("<tr><td><strong>").append(esc(noFormat(lang("gui.mhc.phone.app.office")))).append("</strong></td><td>")
                .append(T("Create or manage your agency or group, members and allies.", "事務所やグループ・メンバー・仲間を作ったり、まとめたりします。")).append("</td><td>").append(T("Free", "無料")).append("</td></tr>");
        for (String a : apps)
            b.append("<tr><td><strong>").append(esc(noFormat(lang("gui.mhc.phone.app." + a)))).append("</strong></td><td>")
                    .append(esc(noFormat(lang("gui.mhc.phone.desc." + a)))).append("</td><td><b>").append(a.equals("herotube") ? tubeCost : appCost).append("</b></td></tr>");
        b.append("</tbody></table></div>");
        b.append("<h2 class=\"band student\" id=\"herotube\">HeroTube</h2><p>").append(T("Watch videos to learn moves. Each video costs <b>skill points</b> (free in Creative mode). "
                + "Capture Scarf moves need Aizawa's costume (body) and syringe moves need Himiko Toga's costume Ver2 (body) to be used.",
                "動画を見ると技を覚えられます。動画ごとに<b>スキルポイント</b>が要ります（クリエイティブでは無料）。"
                + "捕縛布の技は相澤のコスチューム（胴）、注射器の技はトガヒミコのコスチューム Ver2（胴）を着ている時だけ使えます。")).append("</p>");
        for (var c : channels.entrySet()) {
            String cn = noFormat(lang("gui.mhc.herotube.channel." + c.getKey()));
            b.append("<h2>").append(esc(cn.isEmpty() ? titleCase(c.getKey()) : cn)).append("</h2><div class=\"table\"><table><thead><tr><th>").append(T("Video", "動画"))
                    .append("</th><th>").append(T("Move", "覚える技")).append("</th><th>").append(T("Points", "ポイント")).append("</th></tr></thead><tbody>");
            for (String[] v : c.getValue()) {
                String title = noFormat(lang("gui.mhc.herotube.video." + v[0]));
                b.append("<tr><td>").append(esc(title.isEmpty() ? skillName(v[0]) : title)).append("</td><td>").append(esc(skillName(v[0])))
                        .append("</td><td><b>").append(v[1]).append("</b></td></tr>");
            }
            b.append("</tbody></table></div>");
        }
        return layout(T("Smartphone", "スマホ"), T("The smartphone in the My Hero Craft Minecraft mod: hero agencies, villain groups, allies, apps and HeroTube moves.",
                "マイクラ MOD「My Hero Craft」のスマホ。ヒーロー事務所・ヴィラングループ・仲間・アプリ・HeroTube の技。"), "smartphone.html", b.toString(), 0);
    }

    static String guidePage() {
        String main = "<h1>" + T("Guide", "ガイド") + "</h1>"
                + "<h2 id=\"controls\">" + T("Controls", "操作") + "</h2><div class=\"table\"><table><thead><tr><th>" + T("Key", "キー") + "</th><th>" + T("Action", "できること") + "</th></tr></thead><tbody>"
                + row("H", T("Toggle Hero Mode. Most quirk skills are used in Hero Mode.", "ヒーローモードのオン・オフ。個性の技はほとんどヒーローモードで使います。"))
                + row(T("Left click", "左クリック"), T("Use the selected skill (hold to charge skills that can be charged).", "選んでいる技を使う（溜められる技は長押しで溜める）。"))
                + row(T("R / mouse wheel", "R / マウスホイール"), T("Switch the selected skill.", "使う技を切り替える。"))
                + row(T("1 – 9 (Hero Mode)", "1〜9（ヒーローモード中）"), T("Select the skill in that slot directly.", "その番号の技を直接選ぶ。"))
                + row(T("C (Hero Mode)", "C（ヒーローモード中）"), T("Blink in the direction you are moving. 10 second cooldown, shown above the hunger bar.", "動いている方向へブリンクする。CD は10秒で、満腹度ゲージの上に出る。"))
                + row("N", T("Open the Quirk Note (skill tree).", "個性ノート（スキルツリー）を開く。"))
                + row("K", T("Open the status screen to spend status points.", "ステータス画面を開いて、ステータスポイントを振る。"))
                + row(T("Shift twice", "Shift を2回"), T("Triggers some passives (for example climbing). Only works while a skill of that quirk is selected.", "一部のパッシブを使う（壁登りなど）。その個性の技を選んでいる時だけ。"))
                + "</tbody></table></div><p class=\"note\">" + T("All keys can be changed in Options → Controls → Key Binds.", "キーは「設定 → 操作設定 → キー割り当て」で変えられます。") + "</p>"
                + "<h2 id=\"modes\">" + T("Hero and Villain worlds", "ヒーローとヴィラン") + "</h2><p>"
                + T("Each player is either on the hero side or the villain side, decided the first time they join a world "
                + "(the <code>mhcVillainMode</code> game rule decides it for new players). Defeating characters of the other side gives skill points.",
                "プレイヤーはヒーロー側かヴィラン側のどちらかで、ワールドに初めて入った時に決まります（新しく入る人は、ゲームルール <code>mhcVillainMode</code> で決まります）。"
                + "反対側のキャラを倒すと、スキルポイントがもらえます。") + "</p>"
                + "<h2 id=\"points\">" + T("Skill points", "スキルポイント") + "</h2><p>" + T("When you defeat a hero or villain of the other side, every player who damaged it gets skill points:",
                "反対側のヒーローやヴィランを倒すと、ダメージを与えた全員がスキルポイントをもらえます。") + "</p>"
                + "<p class=\"formula\">" + T("points = (sum of the enemy's 5 stats) ÷ 4 × (your damage ÷ enemy max health) × config multiplier",
                "ポイント ＝ 相手の5つのステータスの合計 ÷ 4 × （与えたダメージ ÷ 相手の最大体力） × 設定の倍率") + "</p>"
                + "<p>" + T("You always get at least 1 point if you dealt damage. For example, defeating Sir Nighteye alone gives about 20 points, and a random villain gives about 2–3 (up to 5). "
                + "In Creative mode, points are unlimited.",
                "ダメージを与えていれば、最低でも1ポイントもらえます。たとえばサー・ナイトアイを1人で倒すと約20ポイント、モブのヴィランなら約2〜3ポイント（最大5）です。"
                + "クリエイティブでは、ポイントは無限です。") + "</p>"
                + "<h2 id=\"tree\">" + T("Skill tree", "スキルツリー") + "</h2><p>" + T("Open the Quirk Note to see your quirk's skill tree. Passives are unlocked from the start. "
                + "Each skill costs points and needs the skill before it. Skills marked <span class=\"tag goal\">Goal</span> or <span class=\"tag challenge\">Challenge</span> are the strongest ones.",
                "個性ノートを開くと、自分の個性のスキルツリーが見られます。パッシブは最初から使えます。"
                + "技を覚えるにはポイントと、その前の技が必要です。<span class=\"tag goal\">ゴール</span>や<span class=\"tag challenge\">チャレンジ</span>の印の技が、強い技です。") + "</p>"
                + "<h2 id=\"cooldowns\">" + T("Cooldowns", "クールダウン") + "</h2><p>" + T("After you use a skill it has a cooldown and cannot be used again until it ends. Cooldowns reset when you respawn.",
                "技を使うとクールダウン（CD）が付き、終わるまでは同じ技を使えません。死んで復活すると、CD は全部なくなります。") + "</p>"
                + "<h2 id=\"config\">" + T("Config (mhc-common.toml)", "設定ファイル（mhc-common.toml）") + "</h2><div class=\"table\"><table><thead><tr><th>" + T("Option", "項目")
                + "</th><th>" + T("Default", "最初の値") + "</th><th>" + T("What it does", "内容") + "</th></tr></thead><tbody>"
                + row3("Kosei", "false", T("Ignore the activation conditions of certain quirks.", "一部の個性の発動条件を無視する。"))
                + row3("ParadeMaxGeneration", "7", T("How many generations of clones Sad Man's Parade can make.", "サッドマンズパレードで分身の分身を作れる世代の上限。"))
                + row3("Monster / Villain / Hero", "false / true / true", T("Whether vanilla monsters, villains and heroes spawn.", "モンスター・ヴィラン・ヒーローがスポーンするか。"))
                + row3("Flash / Distortion / Shake / Chromatic", "true", T("Screen effects of big skills.", "大技の画面の演出（閃光・ゆがみ・揺れ・色収差）。"))
                + row3("Exp", "1.0", T("Experience multiplier when defeating villains.", "ヴィランを倒した時の経験値の倍率。"))
                + row3("Points", "1.0", T("Skill point multiplier when defeating characters of the other side.", "反対側のキャラを倒した時のスキルポイントの倍率。"))
                + row3("FriendlyFire", "false", T("Whether skills can hurt allies.", "味方にも技が当たるか。"))
                + "</tbody></table></div>";
        return layout(T("Guide", "ガイド"), T("How to play My Hero Craft: controls, Hero Mode, blink, skill points, skill tree, cooldowns and config options.",
                "マイクラ MOD「My Hero Craft」の遊び方。操作・ヒーローモード・ブリンク・スキルポイント・スキルツリー・クールダウン・設定。"), "guide.html", main, 0);
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
            body{margin:0;color:var(--ink);font:16px/1.65 "Noto Sans","Noto Sans JP",system-ui,-apple-system,"Segoe UI",sans-serif;
              background:var(--paper) radial-gradient(rgba(22,24,29,.07) 1px,transparent 1.2px) 0 0/14px 14px}
            a{color:var(--hero);font-weight:600}.wrap{max-width:1100px;margin:0 auto;padding:0 16px}
            .top{background:var(--navy);border-bottom:4px solid var(--gold);position:sticky;top:0;z-index:5}
            .top .wrap{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:60px;flex-wrap:wrap}
            .brand{display:inline-block;font-family:Bangers,Impact,sans-serif;letter-spacing:1.5px;font-size:28px;color:#fff;text-decoration:none;transform:skewX(-6deg)}
            .brand span{color:var(--gold)}
            nav a{margin-left:18px;text-decoration:none;color:#fff;font-weight:800;letter-spacing:.5px;text-transform:uppercase;font-size:14px}
            nav a:hover{color:var(--gold)}
            nav a.lang{margin-left:22px;padding:3px 12px;border:2px solid var(--gold);border-radius:999px;color:var(--gold);text-transform:none}
            nav a.lang:hover{background:var(--gold);color:var(--navy)}
            html[lang=ja] h1,html[lang=ja] h2,html[lang=ja] .title,html[lang=ja] .card h2{font-family:Bangers,"Noto Sans JP",sans-serif;font-weight:900;letter-spacing:.5px}
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
