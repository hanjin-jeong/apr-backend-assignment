import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

// MiniHttpServer 스모크 테스트: 서버를 데몬 스레드로 띄우고 핵심 동작을 확인한다.
// 외부 라이브러리 없이 실행: javac MiniHttpServer.java MiniHttpServerTest.java && java MiniHttpServerTest
// (8080 포트가 비어 있어야 한다.)
public final class MiniHttpServerTest {

    private static final int PORT = 8080;
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Thread server = new Thread(() -> {
            try {
                MiniHttpServer.main(new String[0]);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        server.setDaemon(true);
        server.start();
        waitUntilUp();

        check("GET /health -> 200", statusOf(send("GET /health HTTP/1.1\r\nHost: x\r\n\r\n")) == 200);
        check("GET /nope -> 404", statusOf(send("GET /nope HTTP/1.1\r\nHost: x\r\n\r\n")) == 404);
        check("POST /health -> 405", statusOf(send("POST /health HTTP/1.1\r\nHost: x\r\n\r\n")) == 405);
        check("malformed -> 400", statusOf(send("GARBAGE\r\n\r\n")) == 400);
        check("GET /work -> 200 done", bodyOf(send("GET /work HTTP/1.1\r\nHost: x\r\n\r\n")).equals("done"));
        check("GET /work?bytes=5 -> aaaaa", bodyOf(send("GET /work?bytes=5 HTTP/1.1\r\nHost: x\r\n\r\n")).equals("aaaaa"));
        check("GET /work?bytes=abc -> 400", statusOf(send("GET /work?bytes=abc HTTP/1.1\r\nHost: x\r\n\r\n")) == 400);
        check("header timeout -> 408", timeoutStatus() == 408);
        checkOverload();
        checkHealthUnderLoad();

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // 헤더 종료 빈 줄을 안 보내고 응답을 기다린다 -> 1초 후 408.
    private static int timeoutStatus() throws IOException {
        try (Socket s = new Socket("127.0.0.1", PORT)) {
            s.setSoTimeout(3000);
            s.getOutputStream().write("GET /health HTTP/1.1\r\nHost: x\r\n".getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            return statusOf(readAll(s.getInputStream()));
        }
    }

    // 동시 20개 -> pool(worker4+큐8) 초과분은 503, 일부는 200.
    private static void checkOverload() throws InterruptedException {
        int n = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger got503 = new AtomicInteger();
        AtomicInteger got200 = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    int st = statusOf(send("GET /work HTTP/1.1\r\nHost: x\r\n\r\n"));
                    if (st == 503) {
                        got503.incrementAndGet();
                    } else if (st == 200) {
                        got200.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        check("overload: 503 발생", got503.get() > 0);
        check("overload: 일부 200", got200.get() > 0);
    }

    // /work로 pool을 포화시킨 상태에서 /health가 빠르게 응답하는지.
    private static void checkHealthUnderLoad() throws Exception {
        for (int i = 0; i < 12; i++) {
            new Thread(() -> {
                try {
                    send("GET /work HTTP/1.1\r\nHost: x\r\n\r\n");
                } catch (Exception ignored) {
                }
            }).start();
        }
        Thread.sleep(200);
        long t0 = System.nanoTime();
        int st = statusOf(send("GET /health HTTP/1.1\r\nHost: x\r\n\r\n"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("health under load -> 200 & <500ms (" + ms + "ms)", st == 200 && ms < 500);
    }

    private static String send(String request) throws IOException {
        try (Socket s = new Socket("127.0.0.1", PORT)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readAll(s.getInputStream());
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    private static int statusOf(String resp) {
        if (resp == null || !resp.startsWith("HTTP/")) {
            return -1;
        }
        return Integer.parseInt(resp.substring(0, resp.indexOf("\r\n")).split(" ")[1]);
    }

    private static String bodyOf(String resp) {
        int i = resp.indexOf("\r\n\r\n");
        return i >= 0 ? resp.substring(i + 4) : "";
    }

    private static void check(String name, boolean ok) {
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", name);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }

    private static void waitUntilUp() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 200);
                return;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        throw new RuntimeException("server did not start on :" + PORT);
    }

    private MiniHttpServerTest() {
    }
}
