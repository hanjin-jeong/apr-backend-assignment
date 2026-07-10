import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class MiniHttpServer {

    private static final int PORT = 8080;

    public static void main(String[] args) throws IOException {
        ServerSocket server = new ServerSocket(PORT);
        System.out.println("MiniHttpServer listening on :" + PORT);
        while (!server.isClosed()) {
            Socket socket = server.accept();
            Thread connection = new Thread(() -> handleConnection(socket), "conn");
            connection.setDaemon(true);
            connection.start();
        }
    }

    private static void handleConnection(Socket socket) {
        try (socket) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = socket.getOutputStream();

            String requestLine = in.readLine();
            if (requestLine == null) {
                return;
            }

            // request-line: METHOD 공백 target 공백 HTTP-version   e.g. "GET /health HTTP/1.1"
            String[] tokens = requestLine.split(" ");
            if (!isWellFormedRequestLine(tokens)) {
                writeResponse(out, 400, "Bad Request", "Bad Request");
                return;
            }

            String method = tokens[0];
            String path = stripQuery(tokens[1]);

            route(out, method, path);
        } catch (IOException ignored) {
        }
    }

    private static void route(OutputStream out, String method, String path) throws IOException {
        switch (path) {
            case "/health":
                if ("GET".equals(method)) {
                    writeResponse(out, 200, "OK", "OK");
                } else {
                    writeResponse(out, 405, "Method Not Allowed", "Method Not Allowed", "Allow: GET\r\n");
                }
                break;
            default:
                writeResponse(out, 404, "Not Found", "Not Found");
        }
    }

    private static boolean isWellFormedRequestLine(String[] tokens) {
        return tokens.length == 3 && tokens[2].startsWith("HTTP/");
    }

    private static String stripQuery(String target) {
        int q = target.indexOf('?');
        return q >= 0 ? target.substring(0, q) : target;
    }

    private static void writeResponse(OutputStream out, int status, String reason, String body)
            throws IOException {
        writeResponse(out, status, reason, body, "");
    }

    private static void writeResponse(OutputStream out, int status, String reason, String body,
            String extraHeaders) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + extraHeaders
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private MiniHttpServer() {
    }
}
