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
            if (requestLine == null || requestLine.isBlank()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "";

            if ("GET".equals(method) && "/health".equals(path)) {
                writeResponse(out, 200, "OK", "OK");
            } else {
                writeResponse(out, 404, "Not Found", "Not Found");
            }
        } catch (IOException ignored) {
        }
    }

    private static void writeResponse(OutputStream out, int status, String reason, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private MiniHttpServer() {
    }
}
