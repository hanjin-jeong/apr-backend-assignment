# apr-backend-assignment — MiniHttpServer

Java 표준 라이브러리만으로 구현한 최소 HTTP 서버. 8080 포트로 listen한다.

## 요구 환경
- Java 17 또는 Java 21

## 빌드 & 실행
```bash
javac MiniHttpServer.java
java MiniHttpServer
```

## 엔드포인트
| Method | Path | 응답 |
|---|---|---|
| GET | `/health` | 200 `OK` |

## 동작 확인
```bash
curl -i localhost:8080/health   # 200 OK
curl -i localhost:8080/nope     # 404 Not Found
```

설계 상세는 [DESIGN.md](./DESIGN.md) 참고.
