import com.sun.net.httpserver.*; import java.net.*; import java.nio.file.*;

/** docs フォルダを http://127.0.0.1:8765/ で見られるようにして、ブラウザで開く(3Dモデルの読み込みにはサーバーが要る)。 */
public class Serve { public static void main(String[] a) throws Exception {
  Path root=Path.of(a[0]).toAbsolutePath().normalize(); HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",8765),0);
  s.createContext("/", ex -> { String p=URLDecoder.decode(ex.getRequestURI().getPath(),"UTF-8"); if(p.endsWith("/")) p+="index.html";
    Path f=root.resolve(p.substring(1)).normalize(); byte[] b; int code=200;
    if(!f.startsWith(root)||!Files.isRegularFile(f)){ f=root.resolve("404.html"); code=404; }
    b=Files.readAllBytes(f); String n=f.toString();
    ex.getResponseHeaders().set("Content-Type", n.endsWith(".css")?"text/css":n.endsWith(".js")?"text/javascript":n.endsWith(".json")?"application/json":n.endsWith(".png")?"image/png":n.endsWith(".xml")?"application/xml":"text/html; charset=utf-8");
    ex.sendResponseHeaders(code,b.length); ex.getResponseBody().write(b); ex.close(); });
  s.start();
  System.out.println("Wiki: http://127.0.0.1:8765/  (close this window to stop)");
  try { java.awt.Desktop.getDesktop().browse(new URI("http://127.0.0.1:8765/")); } catch (Exception e) { }
} }
