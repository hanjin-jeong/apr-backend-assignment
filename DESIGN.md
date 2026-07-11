# MiniHttpServer 설계

Java 표준 라이브러리(`java.net` socket, `Thread`)만 사용한다. 8080 포트로 listen하며
`/health`와 `/work`를 처리한다. `/work`는 직접 구현한 custom thread pool에서 실행한다.

## 1. 엔드포인트

| Method | Path | 동작 |
|---|---|---|
| GET | `/health` | 200 OK (pool과 무관하게 즉시 응답) |
| GET | `/work` | pool에서 1초 작업 후 200 OK + `done` (`?bytes=N`이면 정확히 N바이트) |

### 오류 응답

| 상황 | 응답 |
|---|---|
| 요청라인이 `method path version` 3토큰이 아니거나 version이 `HTTP/`로 시작하지 않음 | 400 Bad Request |
| 요청라인+헤더가 크기 상한(8KB) 초과 | 400 Bad Request |
| `/work?bytes` 값이 음수·비숫자·정수 범위 초과 | 400 Bad Request |
| 알 수 없는 path | 404 Not Found |
| path는 알지만 method가 허용되지 않음 (예: `/health`에 GET 외) | 405 Method Not Allowed (`Allow` 헤더 포함) |
| 헤더가 1초 내 미완성 | 408 Request Timeout |
| `/work` 처리 대기 한도(pool 큐) 초과 | 503 Service Unavailable |

## 2. 요청 처리 흐름

```
main(acceptor)  accept()
      │  socket 마다
      ▼
connection thread
      ├── 요청 읽기 (요청라인+헤더, 1초 deadline·8KB 상한) ── 초과 → 408 / 400
      ├── 요청라인 파싱 (3토큰·version 검증) ── 실패 → 400 Bad Request
      ├── query string 분리 후 path 라우팅
      └── route
            ├── /health : GET → 200 OK / 그 외 method → 405
            ├── /work   : GET → pool 제출 → (수락) 1초 후 200 `done` / (거절) 503
            │                   그 외 method → 405
            └── 그 외 path → 404
```

## 3. 스레드 구성

- **acceptor (main) 1개** — `accept()` 루프. 연결 수락만 하고 즉시 connection thread로 넘긴다.
- **connection thread (연결당 1개)** — 요청 파싱·라우팅·응답 write. thread-per-connection이라
  느린 client나 disconnect가 연결 단위로 격리된다.
- **work pool worker (고정 4개)** — `/work`의 1초 작업만 실행. 응답 write는 하지 않는다.

## 4. Timeout·크기 제한·연결 정리

- **header read timeout (1초).** `socket.setSoTimeout(1000)`으로 무입력 연결을 끊고, 추가로
  연결 시작부터 1초 deadline을 둬 바이트를 찔끔씩 흘리는 slowloris도 차단한다. 미완성 시 408.
- **크기 상한 (8KB).** 요청라인+헤더를 바이트 단위로 읽으며 누적 크기를 검사한다.
  `BufferedReader.readLine()`은 개행까지 무제한 버퍼링해 거대 요청에서 OOM 위험이 있어
  경계 있는 read로 교체했다. timeout은 "시간"만, 상한은 "크기"만 막으므로 둘 다 필요하다.
- **연결 정리.** thread-per-connection이라 한 연결의 timeout·disconnect·오류가 다른 연결에
  영향을 주지 않는다. client가 요청 전송 중 끊으면 read가 EOF/IOException → 해당 socket만 close.

### 설계 결정: 응답 전 요청 소비(graceful close)

응답 후 close 시 커널 수신버퍼에 안 읽은 요청 데이터가 남으면 OS가 FIN이 아닌 RST를 보내
client가 응답을 받기 전에 끊길 수 있다. 로컬에서 요청 크기별로 실측(8회 반복):

| 요청 크기 | 요청 소비함 | 요청 소비 안 함 |
|---|---|---|
| ≤ 64KB | 손실 0 | 손실 0 |
| 256KB | 손실 0 | 간헐 유실(RESET) |
| 1MB | 손실 0 | 전량 유실(RESET) |

임계값은 소켓 수신버퍼(약 64KB)와 일치. 정상 GET 요청(수 KB)은 소비 여부와 무관하게 무손실이라
소비가 필수는 아니지만, (1) graceful close라는 올바른 동작이고 (2) 헤더 timeout 판정을 위해
어차피 헤더 끝까지 읽어야 하며 (3) 대형 요청은 크기 상한이 400으로 조기 차단하므로,
`readRequest`에서 헤더 블록을 끝까지 읽는 방식으로 함께 처리한다.

## 5. Custom Thread Pool (`/work`)

Java executor 계열을 쓰지 않고 `ArrayDeque` + `synchronized`/`wait`/`notify`로 직접 구현한다.

- **구성**: 고정 worker 4개 + 유계 대기 큐(용량 8). worker는 큐에서 작업을 꺼내 실행한다.
- **제출·거절 정책**: `submit`은 큐가 가득 차 있으면(대기 8개) **즉시 `false`를 반환**하고
  connection thread가 503을 보낸다. 기다리지 않으므로 과부하에서도 빠르게 거절한다.
  동시에 처리 가능한 `/work`는 최대 `worker(4) + 큐(8) = 12`개, 초과분은 503.
- **역할 분리 (설계 결정)**: worker는 1초 작업만 수행하고 **응답 write는 connection thread**가 한다.
  worker가 느린 client의 소켓 write에 묶이지 않아 slow client가 pool 처리량을 떨어뜨리지 않는다.
- **`/health` 격리**: `/health`는 pool을 거치지 않고 connection thread에서 즉시 응답하므로
  `/work` 큐가 포화되어도 영향을 받지 않는다.

`worker=4`, `큐=8`은 과부하 거절(503)을 관찰 가능한 선에서 잡은 값이며 상수로 조정 가능하다.

### `?bytes=N` — 응답 스트리밍

`bytes=N`이 지정되면 `done` 대신 정확히 N바이트(`'a'`) plain text를 반환한다.

- **스트리밍**: N바이트를 통짜로 할당하지 않고 8KB 버퍼로 나눠 write한다. 따라서 N이 커도
  메모리는 버퍼 크기로 일정(O(버퍼))해 OOM이 없다. `Content-Length: N`은 미리 전송한다.
- **상한**: 별도 캡을 두지 않는다. N은 `Integer.parseInt`로 파싱해 **정수 범위(~2GB)가 자연 상한**이며,
  음수·비숫자·범위 초과는 400. (스펙에 크기 캡이 없고, 임의 캡은 유효한 큰 요청을 malformed(400)로
  오분류하게 되어 두지 않음.)
- **slow client**: 대형 응답을 느리게 읽어도 worker는 1초 작업 후 이미 free이고 write는 connection
  thread가 담당하므로, pool 처리량과 `/health` 응답에 영향이 없다. 남는 것은 그 연결 하나의 점유뿐이다.
