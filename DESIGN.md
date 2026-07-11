# MiniHttpServer 설계

Java 표준 라이브러리(`java.net` socket, `Thread`)만으로 구현한 HTTP 서버. 8080 포트로 listen하며
`/health`와 `/work`를 처리하고, `/work`는 직접 구현한 custom thread pool에서 실행한다.

## 엔드포인트

| Method | Path | 동작 |
|---|---|---|
| GET | `/health` | 200 OK (pool과 무관하게 즉시 응답) |
| GET | `/work` | pool에서 1초 작업 후 200 `done` (`?bytes=N`이면 정확히 N바이트) |

오류 응답: **400**(요청라인 형식 오류·헤더 8KB 초과·`bytes` 값 오류), **404**(알 수 없는 path),
**405**(허용되지 않은 method, `Allow` 포함), **408**(헤더 1초 내 미완성), **503**(`/work` pool 큐 포화).

## 요청 처리 흐름

```
main(acceptor) accept() ──소켓마다──▶ connection thread
    ├ 요청 읽기 (요청라인+헤더, 1초 deadline·8KB 상한) ─ 초과 → 408 / 400
    ├ 요청라인 파싱 (3토큰·version 검증) ─ 실패 → 400
    └ 라우팅 (query 분리 후 path)
        ├ /health : GET → 200 / 그 외 → 405
        ├ /work   : GET → pool 제출 → 수락 시 1초 후 200(done|N bytes) / 거절 시 503
        └ 그 외   : 404
```

## 스레드 구성

- **acceptor (main)** — `accept()` 루프. 수락만 하고 connection thread로 넘긴다.
- **connection thread (연결당 1개)** — 요청 파싱·라우팅·응답 write. 연결 단위로 격리된다.
- **work pool worker (고정 4개)** — `/work`의 1초 작업만 실행 (write는 하지 않음).

## 요청 읽기: timeout·크기 상한·연결 정리

- **1초 timeout**: `setSoTimeout` + 연결 시작 기준 deadline으로 무입력·slowloris를 차단, 미완성 시 408.
- **8KB 상한**: 요청라인+헤더를 바이트 단위로 읽으며 누적 크기 검사(초과 400). `readLine`의 무제한
  버퍼링 대신 경계 있는 read라 거대 요청에도 OOM이 없다.
- **연결 정리**: client가 도중에 끊으면 read가 EOF/IOException → 해당 소켓만 close (다른 연결 무영향).
- 응답 전 헤더 블록을 끝까지 읽는다: 안 읽은 데이터가 남으면 close 시 RST로 응답이 유실될 수 있고,
  timeout 판정상 어차피 끝까지 읽어야 하기 때문.

## Custom thread pool (`/work`)

`ArrayDeque` + `synchronized`/`wait`/`notify`로 직접 구현 (executor·BlockingQueue 계열 미사용).

- **구성**: 고정 worker 4 + 유계 대기 큐 8. worker가 큐에서 작업을 꺼내 실행.
- **제출·거절**: `submit`은 큐가 차 있으면 즉시 `false` → 503 (기다리지 않음). 동시 수용 = worker(4)+큐(8)
  = 12개, 초과분 503. worker·큐 크기는 상수로 조정 가능.
- **`?bytes=N`**: `done` 대신 N바이트(`'a'`)를 8KB 버퍼로 나눠 스트리밍(메모리 O(버퍼), Content-Length
  미리 전송). 별도 캡 없이 int 범위를 자연 상한으로 삼고, 음수·비숫자·범위 초과는 400.

## 설계 trade-off

- **thread-per-connection**: 단순하고 연결 단위 격리에 유리하나 동시 연결 수 상한이 없다(connection flood).
- **write를 connection thread가 담당**: worker를 소켓 write에서 분리해 느린 client가 pool 처리량을 깎지
  않는다. 대신 느린 reader는 자기 connection thread를 오래 점유(그 연결만 느림, `/health` 무영향).
- **유계 큐 + 즉시 거절**: 과부하를 빠르게 쳐낸다. 큐를 키우면 덜 거절하지만 대기 지연↑(≈ 큐÷worker×작업시간).
- **읽기 timeout O / 쓰기 timeout X**: Java blocking 소켓엔 write timeout이 없어, 느린 write는 연결 격리로
  흡수하고 `/health` 무지연만 보장한다.
- **connection flood**: acceptor가 연결당 스레드를 무제한 생성 → 인지하되, 스펙의 과부하 대응은 `/work`
  pool+503으로 한정되고 1초 header timeout이 유휴 연결을 정리하므로 연결 수 제한은 두지 않는다.

## 테스트

`MiniHttpServerTest.java` — 외부 라이브러리 없이 서버를 데몬 스레드로 띄워 핵심 동작(`/health`·`/work`·
`bytes`·오류코드·과부하 503·과부하 중 `/health` 무지연)을 확인하는 스모크 테스트.

```bash
javac MiniHttpServer.java MiniHttpServerTest.java && java MiniHttpServerTest   # 8080 비어 있어야 함
```
