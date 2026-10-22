import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

public class xxfilehost {
    static final File ROOT = new File("shared");
    static final int PORT = 8080;
    static String displayAddr = "http://127.0.0.1:" + PORT;

    public static void main(String[] args) throws Exception {
        ROOT.mkdirs();
        String ip = getLocalIp();
        displayAddr = "http://" + ip + ":" + PORT;

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new MainHandler());
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();

        SwingUtilities.invokeLater(() -> showWindow(ip));
    }

    static String getLocalIp() {
        try {
            Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface iface = ifs.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                for (java.net.InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address) return addr.getHostAddress();
                }
            }
        } catch (Exception e) {}
        return "127.0.0.1";
    }

    static void showWindow(String ip) {
        JFrame frame = new JFrame("xxfilehost / xxhuntap.xo.je");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(560, 240);
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());

        JLabel title = new JLabel("xxfilehost", SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 22));
        title.setBorder(BorderFactory.createEmptyBorder(15, 10, 5, 10));

        JLabel info = new JLabel(
            "<html><div style='text-align:center;'>Server is running.<br><br>"
            + "Open this address on your mobile device<br><b style='font-size:16px;'>" + displayAddr + "</b><br>"
            + "to access files for sharing. (App made by xxhuntap)</div></html>",
            SwingConstants.CENTER
        );
        info.setFont(new Font("SansSerif", Font.PLAIN, 14));

        JLabel path = new JLabel("Shared folder: " + ROOT.getAbsolutePath(), SwingConstants.CENTER);
        path.setFont(new Font("SansSerif", Font.PLAIN, 11));
        path.setForeground(Color.GRAY);
        path.setBorder(BorderFactory.createEmptyBorder(5, 10, 15, 10));

        frame.add(title, BorderLayout.NORTH);
        frame.add(info, BorderLayout.CENTER);
        frame.add(path, BorderLayout.SOUTH);
        frame.setVisible(true);
    }

    static class MainHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String path = ex.getRequestURI().getPath();
                String rawPath = ex.getRequestURI().getRawPath();
                String method = ex.getRequestMethod();
                String query = ex.getRequestURI().getQuery();

                if (path.equals("/") && method.equals("GET")) {
                    sendListing(ex, query);
                    return;
                }

                if (method.equals("GET") && rawPath.startsWith("/file/")) {
                    handleDownload(ex, rawPath);
                    return;
                }

                if (method.equals("POST") && path.equals("/upload")) {
                    handleUpload(ex);
                    return;
                }

                if (method.equals("POST") && path.equals("/rename")) {
                    handleRename(ex);
                    return;
                }

                if (method.equals("POST") && path.equals("/delete")) {
                    handleDelete(ex);
                    return;
                }

                sendText(ex, 404, "404");
            } catch (Exception e) {
                sendText(ex, 500, "Error: " + e.getMessage());
            }
        }

        void handleDownload(HttpExchange ex, String rawPath) throws IOException {
            String name = URLDecoder.decode(rawPath.substring(6), StandardCharsets.UTF_8);
            File f = new File(ROOT, name);
            if (!f.exists() || !f.isFile() || !f.getCanonicalPath().startsWith(ROOT.getCanonicalPath())) {
                sendText(ex, 404, "Not found");
                return;
            }
            String encodedName = URLEncoder.encode(f.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().add("Content-Disposition",
                "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);
            ex.sendResponseHeaders(200, f.length());
            try (OutputStream os = ex.getResponseBody(); FileInputStream fis = new FileInputStream(f)) {
                fis.transferTo(os);
            }
        }

        void handleRename(HttpExchange ex) throws IOException {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String[] params = body.split("&");
            String oldName = "", newName = "";
            
            for (String p : params) {
                if (p.startsWith("old=")) oldName = URLDecoder.decode(p.substring(4), StandardCharsets.UTF_8);
                if (p.startsWith("new=")) newName = URLDecoder.decode(p.substring(4), StandardCharsets.UTF_8);
            }

            File oldFile = new File(ROOT, oldName);
            if (!oldFile.exists()) {
                sendText(ex, 404, "File not found");
                return;
            }

            newName = newName.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
            if (newName.isEmpty()) newName = oldFile.getName();

            File newFile = new File(ROOT, newName);
            if (newFile.exists() && !oldFile.getName().equals(newFile.getName())) {
                String base = newName;
                String ext = "";
                int dot = newName.lastIndexOf('.');
                if (dot > 0) { base = newName.substring(0, dot); ext = newName.substring(dot); }
                int i = 1;
                while (newFile.exists()) {
                    newFile = new File(ROOT, base + "_" + (i++) + ext);
                }
            }

            if (oldFile.renameTo(newFile)) { }
            
            ex.getResponseHeaders().add("Location", "/");
            ex.sendResponseHeaders(303, -1);
            ex.close();
        }

        void handleDelete(HttpExchange ex) throws IOException {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String[] params = body.split("&");
            String targetName = "";
            
            for (String p : params) {
                if (p.startsWith("target=")) targetName = URLDecoder.decode(p.substring(7), StandardCharsets.UTF_8);
            }

            File targetFile = new File(ROOT, targetName);
            if (targetFile.exists() && targetFile.isFile() && targetFile.getCanonicalPath().startsWith(ROOT.getCanonicalPath())) {
                if (targetFile.delete()) { }
            }
            
            ex.getResponseHeaders().add("Location", "/");
            ex.sendResponseHeaders(303, -1);
            ex.close();
        }

        void sendListing(HttpExchange ex, String query) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("<!doctype html><html><head><meta charset='utf-8'>");
            sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
            sb.append("<title>xxfilehost</title>");
            sb.append("<style>");
            sb.append("body { font-family: sans-serif; max-width: 800px; margin: 20px auto; padding: 0 10px; }");
            sb.append(".btn { padding: 8px 15px; background: #007bff; color: white; border: none; border-radius: 5px; cursor: pointer; font-size: 16px; }");
            sb.append(".btn:hover { background: #0056b3; }");
            sb.append(".btn-small { padding: 3px 8px; font-size: 12px; margin-left: 5px; background: #6c757d; }");
            sb.append(".btn-danger { background: #dc3545; }");
            sb.append(".btn-danger:hover { background: #c82333; }");
            sb.append(".btn-disabled { background: #ccc; cursor: not-allowed; }");
            sb.append(".accordion { background-color: #f1f1f1; color: #444; cursor: pointer; padding: 10px; width: 100%; border: none; text-align: left; outline: none; font-size: 14px; border-radius: 5px; margin-top: 20px; }");
            sb.append(".active, .accordion:hover { background-color: #ccc; }");
            sb.append(".panel { padding: 0 18px; display: none; background-color: white; overflow: hidden; font-size: 14px; line-height: 1.5; color: #333; }");
            sb.append(".file-item { display: flex; justify-content: space-between; align-items: center; padding: 5px 0; }");
            sb.append(".success-box { background: #d4edda; color: #155724; padding: 10px; border-radius: 5px; margin-bottom: 15px; border: 1px solid #c3e6cb; }");
            sb.append(".modal { display: none; position: fixed; z-index: 1; left: 0; top: 0; width: 100%; height: 100%; background-color: rgba(0,0,0,0.5); }");
            sb.append(".modal-content { background-color: #fefefe; margin: 15% auto; padding: 20px; border: 1px solid #888; width: 80%; max-width: 400px; border-radius: 8px; text-align: center; }");
            sb.append("</style></head>");
            sb.append("<body>");
            sb.append("<h2>xxfilehost</h2>");

            if (query != null && query.startsWith("success=")) {
                String saved = URLDecoder.decode(query.substring(8), StandardCharsets.UTF_8);
                sb.append("<div class='success-box'><b>Success!</b> File saved on PC as: <br><b>").append(escape(saved)).append("</b></div>");
            }
            
            sb.append("<form method='post' action='/upload' enctype='multipart/form-data'>");
            sb.append("<p><input type='file' name='f' required style='font-size: 16px;'></p>");
            sb.append("<button type='submit' class='btn'>Upload to PC</button>");
            sb.append("</form><hr>");

            File[] files = ROOT.listFiles();
            if (files == null || files.length == 0) {
                sb.append("<p>Folder is empty.</p>");
            } else {
                Arrays.sort(files, Comparator.comparing(File::getName));
                sb.append("<ul style='list-style-type: none; padding: 0;'>");
                for (File f : files) {
                    if (f.isFile()) {
                        String enc = URLEncoder.encode(f.getName(), StandardCharsets.UTF_8).replace("+", "%20");
                        String safeName = escape(f.getName());
                        sb.append("<li class='file-item'><div><a href='/file/").append(enc).append("'>")
                          .append(safeName).append("</a> <small>")
                          .append(f.length() / 1024).append(" KB</small></div><div>");
                        sb.append("<button class='btn btn-small' onclick=\"renameFile('").append(safeName.replace("'", "\\'")).append("')\">Rename</button>");
                        sb.append("<button class='btn btn-small btn-danger' onclick=\"deleteFile('").append(safeName.replace("'", "\\'")).append("')\">Delete</button>");
                        sb.append("</div></li>");
                    }
                }
                sb.append("</ul>");
            }

            sb.append("<button class='accordion' onclick=\"toggleAccordion(this)\">Why can the file name or format change?</button>");
            sb.append("<div class='panel'>");
            sb.append("<p><b>Why does this happen?</b><br>");
            sb.append("Sometimes mobile browsers hide the original file name for security reasons, transferring only the raw data.</p>");
            sb.append("<p><b>How does the program work?</b><br>");
            sb.append("Our server receives the file and tries to determine its type. If the format is unknown, it might save it with a temporary name.</p>");
            sb.append("<p style='background: #fff3cd; padding: 10px; border-radius: 5px; border-left: 4px solid #ffc107;'><b>IMPORTANT:</b> The program <b>DOES NOT convert</b> the file. It only changes its extension so Windows knows which program to use.</p>");
            sb.append("<p><b>What to do if the format is wrong?</b><br>");
            sb.append("Just click the <b>Rename</b> button next to the file in the list above and enter the correct name.</p>");
            sb.append("</div>");

            sb.append("<div id='deleteModal' class='modal'>");
            sb.append("  <div class='modal-content'>");
            sb.append("    <h3>Are you sure?</h3>");
            sb.append("    <p style='font-weight:bold; color:#dc3545;' id='modalFileName'></p>");
            sb.append("    <p>Do you really want to delete this file?</p>");
            sb.append("    <button id='finalDeleteBtn' class='btn btn-disabled' disabled onclick='confirmFinalDelete()'>Delete</button>");
            sb.append("    <button class='btn btn-small' style='background:#6c757d;' onclick='closeModal()'>Cancel</button>");
            sb.append("  </div>");
            sb.append("</div>");

            sb.append("<script>");
            sb.append("var fileToDelete = '';");
            sb.append("var timerInterval;");
            sb.append("function toggleAccordion(btn) {");
            sb.append("  btn.classList.toggle('active');");
            sb.append("  var panel = btn.nextElementSibling;");
            sb.append("  if (panel.style.display === 'block') { panel.style.display = 'none'; } else { panel.style.display = 'block'; }");
            sb.append("}");
            sb.append("function renameFile(oldName) {");
            sb.append("  var newName = prompt('Enter new file name (with extension):', oldName);");
            sb.append("  if (newName && newName !== oldName) {");
            sb.append("    var form = document.createElement('form');");
            sb.append("    form.method = 'POST'; form.action = '/rename';");
            sb.append("    var input1 = document.createElement('input'); input1.type = 'hidden'; input1.name = 'old'; input1.value = oldName;");
            sb.append("    var input2 = document.createElement('input'); input2.type = 'hidden'; input2.name = 'new'; input2.value = newName;");
            sb.append("    form.appendChild(input1); form.appendChild(input2);");
            sb.append("    document.body.appendChild(form); form.submit();");
            sb.append("  }");
            sb.append("}");
            sb.append("function deleteFile(name) {");
            sb.append("  fileToDelete = name;");
            sb.append("  document.getElementById('modalFileName').innerText = name;");
            sb.append("  document.getElementById('deleteModal').style.display = 'block';");
            sb.append("  var btn = document.getElementById('finalDeleteBtn');");
            sb.append("  btn.disabled = true;");
            sb.append("  btn.classList.add('btn-disabled');");
            sb.append("  btn.classList.remove('btn-danger');");
            sb.append("  var timeLeft = 3;");
            sb.append("  btn.innerText = 'Delete (' + timeLeft + ')';");
            sb.append("  clearInterval(timerInterval);");
            sb.append("  timerInterval = setInterval(function() {");
            sb.append("    timeLeft--;");
            sb.append("    if (timeLeft <= 0) {");
            sb.append("      clearInterval(timerInterval);");
            sb.append("      btn.disabled = false;");
            sb.append("      btn.classList.remove('btn-disabled');");
            sb.append("      btn.classList.add('btn-danger');");
            sb.append("      btn.innerText = 'DELETE NOW';");
            sb.append("    } else {");
            sb.append("      btn.innerText = 'Delete (' + timeLeft + ')';");
            sb.append("    }");
            sb.append("  }, 1000);");
            sb.append("}");
            sb.append("function closeModal() {");
            sb.append("  clearInterval(timerInterval);");
            sb.append("  document.getElementById('deleteModal').style.display = 'none';");
            sb.append("}");
            sb.append("function confirmFinalDelete() {");
            sb.append("  var form = document.createElement('form');");
            sb.append("  form.method = 'POST'; form.action = '/delete';");
            sb.append("  var input = document.createElement('input'); input.type = 'hidden'; input.name = 'target'; input.value = fileToDelete;");
            sb.append("  form.appendChild(input);");
            sb.append("  document.body.appendChild(form); form.submit();");
            sb.append("}");
            sb.append("</script>");

            sb.append("</body></html>");
            
            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        }

        void handleUpload(HttpExchange ex) throws IOException {
            String contentType = ex.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.contains("boundary=")) {
                sendText(ex, 400, "No boundary");
                return;
            }
            String boundary = contentType.substring(contentType.indexOf("boundary=") + 9).trim();
            if (boundary.startsWith("\"")) boundary = boundary.substring(1, boundary.length() - 1);

            String savedName = "";
            InputStream is = ex.getRequestBody();
            byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int b;
            while ((b = is.read()) != -1) {
                headerBuf.write(b);
                byte[] arr = headerBuf.toByteArray();
                if (arr.length >= 4) {
                    if (arr[arr.length-4] == 13 && arr[arr.length-3] == 10 && arr[arr.length-2] == 13 && arr[arr.length-1] == 10) {
                        break;
                    }
                }
            }

            String currentHeaders = new String(headerBuf.toByteArray(), StandardCharsets.ISO_8859_1);
            String fieldName = parseFieldName(currentHeaders);
            String originalFileName = parseFilenameUtf8(currentHeaders);

            if (fieldName != null && fieldName.equals("f")) {
                if (originalFileName == null || originalFileName.isEmpty() || originalFileName.equals("null") || originalFileName.equals("blob") || originalFileName.equals("viewport")) {
                    originalFileName = "file_" + System.currentTimeMillis() + ".bin";
                }

                File out = uniqueFile(originalFileName);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int matchCount = 0;
                    int curr;
                    while ((curr = is.read()) != -1) {
                        if (curr == boundaryBytes[matchCount]) {
                            matchCount++;
                            if (matchCount == boundaryBytes.length) break;
                        } else {
                            if (matchCount > 0) {
                                for (int i = 0; i < matchCount; i++) fos.write(boundaryBytes[i]);
                                matchCount = 0;
                            }
                            if (curr == boundaryBytes[0]) {
                                matchCount = 1;
                            } else {
                                fos.write(curr);
                            }
                        }
                    }
                }
                savedName = out.getName();
            }

            String redirect = "/";
            if (!savedName.isEmpty()) {
                redirect += "?success=" + URLEncoder.encode(savedName, StandardCharsets.UTF_8);
            }
            ex.getResponseHeaders().add("Location", redirect);
            ex.sendResponseHeaders(303, -1);
            ex.close();
        }

        String parseFieldName(String headers) {
            int i = headers.indexOf("name=\"");
            if (i < 0) return null;
            int j = headers.indexOf("\"", i + 6);
            if (j < 0) return null;
            return headers.substring(i + 6, j);
        }

        String parseFilenameUtf8(String headers) {
            int f = headers.indexOf("filename=\"");
            if (f > -1) {
                int fe = headers.indexOf("\"", f + 10);
                if (fe > -1) {
                    String raw = headers.substring(f + 10, fe);
                    try {
                        byte[] bytes = raw.getBytes(StandardCharsets.ISO_8859_1);
                        String utf = new String(bytes, StandardCharsets.UTF_8);
                        return utf.contains("\uFFFD") ? raw : utf;
                    } catch (Exception e) {
                        return raw;
                    }
                }
            }
            return null;
        }

        File uniqueFile(String name) {
            if (name == null) name = "unknown";
            name = name.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
            if (name.isEmpty()) name = "unknown";
            File f = new File(ROOT, name);
            int n = 1;
            while (f.exists()) {
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot) : "";
                f = new File(ROOT, base + "_" + (n++) + ext);
            }
            return f;
        }

        void sendText(HttpExchange ex, int code, String msg) throws IOException {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        }

        String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }
    }
}
