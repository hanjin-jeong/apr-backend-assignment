# MiniHttpServer 설계

Java 표준 라이브러리(`java.net` socket, `Thread`)만 사용한다. 8080 포트로 listen하며
현재 `/health` 를 처리한다. 이후 PR에서 `/work`, custom thread pool, timeout/slow client
처리를 더해간다.

## 1. 엔드포인트

| Method | Path | 동작 |
|---|---|---|
| GET | `/health` | 200 OK |

### 오류 응답

| 상황 | 응답 |
|---|---|
| 요청라인이 `method path version` 3토큰이 아니거나 version이 `HTTP/`로 시작하지 않음 | 400 Bad Request |
| 알 수 없는 path | 404 Not Found |
| path는 알지만 method가 허용되지 않음 (예: `/health`에 GET 외) | 405 Method Not Allowed (`Allow` 헤더 포함) |

`408`(header timeout), `503`(과부하)은 이후 PR에서 `/work`·custom thread pool과 함께 추가한다.

## 2. 요청 처리 흐름

```
main(acceptor)  accept()
      │  socket 마다
      ▼
connection thread
      ├── 요청라인 파싱 (3토큰·version 검증) ── 실패 → 400 Bad Request
      ├── query string 분리 후 path 라우팅
      └── route
            ├── /health : GET → 200 OK / 그 외 method → 405 Method Not Allowed
            └── 그 외 path       → 404 Not Found
```

## 3. 스레드 구성

- **acceptor (main) 1개** — `accept()` 루프. 연결 수락만 하고 즉시 connection thread로 넘긴다.
- **connection thread (연결당 1개)** — 요청 파싱·라우팅·응답 write. thread-per-connection이라
  느린 client나 disconnect가 연결 단위로 격리된다.
