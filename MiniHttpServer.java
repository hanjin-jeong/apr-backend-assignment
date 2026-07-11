import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

public final class MiniHttpServer {

    private static final int PORT = 8080;
    private static final int HEADER_TIMEOUT_MS = 1000;
    private static final int MAX_REQUEST_BYTES = 8 * 1024;
    private static final int WORK_DURATION_MS = 1000;
    private static final int WORK_WORKERS = 4;
    private static final int WORK_QUEUE_CAPACITY = 8;

    private static final WorkerPool WORK_POOL = new WorkerPool(WORK_WORKERS, WORK_QUEUE_CAPACITY);

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
            socket.setSoTimeout(HEADER_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String requestLine;
            try {
                requestLine = readRequest(in);
            } catch (SocketTimeoutException e) {
                writeResponse(out, 408, "Request Timeout", "Request Timeout");
                return;
            } catch (RequestTooLargeException e) {
                writeResponse(out, 400, "Bad Request", "Bad Request");
                return;
            }
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
            // client disconnect 등은 해당 connection만 정리 (thread-per-connection이라 격리됨)
        }
    }

    // 요청라인 + 헤더 블록(빈 줄까지)을 1초 deadline·크기 상한 안에서 읽고 요청라인을 반환한다.
    // 안 읽은 데이터를 남긴 채 close하면 RST로 응답이 유실될 수 있어, 응답 전 요청을 여기서 소비한다.
    // 헤더 완성 전 client가 끊으면 null (연결만 정리).
    private static String readRequest(InputStream in) throws IOException {
        long deadline = System.currentTimeMillis() + HEADER_TIMEOUT_MS;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        String requestLine = null;
        int total = 0;
        while (true) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            if (++total > MAX_REQUEST_BYTES) {
                throw new RequestTooLargeException();
            }
            if (System.currentTimeMillis() > deadline) {
                throw new SocketTimeoutException("header deadline exceeded");
            }
            if (b != '\n') {
                line.write(b);
                continue;
            }
            String s = line.toString(StandardCharsets.ISO_8859_1);
            line.reset();
            if (s.endsWith("\r")) {
                s = s.substring(0, s.length() - 1);
            }
            if (requestLine == null) {
                if (!s.isEmpty()) {
                    requestLine = s;   // 첫 비어있지 않은 줄 = 요청라인 (선행 빈 줄은 무시)
                }
            } else if (s.isEmpty()) {
                return requestLine;    // 빈 줄 = 헤더 끝
            }
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
            case "/work":
                if ("GET".equals(method)) {
                    handleWork(out);
                } else {
                    writeResponse(out, 405, "Method Not Allowed", "Method Not Allowed", "Allow: GET\r\n");
                }
                break;
            default:
                writeResponse(out, 404, "Not Found", "Not Found");
        }
    }

    // 1초 작업을 custom thread pool에서 실행한다. 큐가 가득 차 있으면 503.
    // worker는 작업(1초)만 수행하고 응답 write는 이 connection thread가 담당한다.
    // → 느린 client의 read가 worker를 붙잡지 않아 work 처리량이 보호된다.
    private static void handleWork(OutputStream out) throws IOException {
        WorkResult result = new WorkResult();
        boolean accepted = WORK_POOL.submit(() -> {
            try {
                Thread.sleep(WORK_DURATION_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                result.signalDone();
            }
        });
        if (!accepted) {
            writeResponse(out, 503, "Service Unavailable", "Service Unavailable");
            return;
        }
        try {
            result.awaitDone();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        writeResponse(out, 200, "OK", "done");
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

    // 직접 구현한 고정 크기 thread pool. 유계 큐가 가득 차면 submit을 즉시 거절한다.
    private static final class WorkerPool {
        private final Deque<Runnable> queue = new ArrayDeque<>();
        private final Object lock = new Object();
        private final int capacity;

        WorkerPool(int workers, int capacity) {
            this.capacity = capacity;
            for (int i = 0; i < workers; i++) {
                Thread worker = new Thread(this::workerLoop, "work-" + i);
                worker.setDaemon(true);
                worker.start();
            }
        }

        boolean submit(Runnable task) {
            synchronized (lock) {
                if (queue.size() >= capacity) {
                    return false;
                }
                queue.addLast(task);
                lock.notify();
                return true;
            }
        }

        private void workerLoop() {
            while (true) {
                Runnable task;
                synchronized (lock) {
                    while (queue.isEmpty()) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    task = queue.pollFirst();
                }
                task.run();
            }
        }
    }

    // pool worker의 작업 완료를 connection thread가 기다리기 위한 신호.
    private static final class WorkResult {
        private final Object lock = new Object();
        private boolean done;

        void signalDone() {
            synchronized (lock) {
                done = true;
                lock.notifyAll();
            }
        }

        void awaitDone() throws InterruptedException {
            synchronized (lock) {
                while (!done) {
                    lock.wait();
                }
            }
        }
    }

    private static final class RequestTooLargeException extends IOException {
    }

    private MiniHttpServer() {
    }
}
