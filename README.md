# LawRAG — HITL RAG pháp luật giao thông đường bộ

**Quyết định đã khóa:** [docs/decisions.md](docs/decisions.md) · **Chính sách nguồn:** [docs/source-policy.md](docs/source-policy.md) · **Data dictionary:** [docs/data-dictionary.md](docs/data-dictionary.md)

Ứng dụng Spring Boot Modular Monolith (PostgreSQL + pgvector, Flyway `V1`–`V8`, Thymeleaf + HTMX + Spring Security) hỗ trợ tra cứu pháp luật giao thông đường bộ Việt Nam theo luồng Human-in-the-Loop (HITL) RAG:

1. **Tra cứu nội bộ (Hybrid Retrieval):** kết hợp Full-Text Search (PostgreSQL) + Vector Cosine Search (`pgvector`, 768 chiều) + Reciprocal Rank Fusion (RRF) + chấm điểm căn cứ (`ContextGrader`) và kiểm tra trích dẫn/hiệu lực (`CitationValidator`).
2. **Xác nhận tìm kiếm Web (HITL Consent):** khi kho nội bộ chưa đủ căn cứ, người dùng xác nhận đồng ý tìm kiếm Web; job bất đồng bộ (`WebSearchWorker`) tìm URL trên nguồn chính thức được allowlist (`vanban.chinhphu.vn`, chống SSRF), nạp & cách ly (`QUARANTINED`) để kiểm duyệt trước khi xuất bản (`READY`).
3. **Quản trị & Cộng đồng:** Admin console (`/admin/**`) duyệt tài liệu/user/job, hệ thống tài khoản đăng nhập/đăng ký (`/login`, `/register`) và diễn đàn thảo luận pháp luật (`/forum`).
4. **Quota & Ngân sách:** cơ chế giữ chỗ/hoàn trả quota (`QuotaReservationService` + ledger) và giới hạn chi phí theo ngày (`CostBudgetService`).

## 1. Kiểm thử chấp nhận (Acceptance Gates G1–G8 & Forum/Auth)

Hệ thống sử dụng PostgreSQL + `pgvector` thật (không dùng H2) cho toàn bộ test tích hợp (`G1AcceptanceTest` – `G8AcceptanceTest`, `ForumAndAuthAcceptanceTest`, `RagEvaluationHarnessTest`, `StartupFailFastTest`, `SecretHygieneTest`).

## 2. Phiên bản đã khóa

| Thành phần | Phiên bản |
| --- | --- |
| Java | 25 LTS |
| Maven | 3.9.16 (qua Maven Wrapper `mvnw` / `mvnw.cmd`) |
| Spring Boot | 3.5.16 |
| Spring Framework | 6.2.19 |
| Flyway | 11.7.2 + `flyway-database-postgresql` 11.7.2 |
| PostgreSQL | 18.6 (Local) |
| pgvector | 0.8.7 |
| PostgreSQL JDBC | 42.7.11 |
| HTMX | 2.0.11 (vendored tại `src/main/resources/static/js/htmx.min.js`) |
| Múi giờ ứng dụng | `Asia/Ho_Chi_Minh` |

## 3. Yêu cầu môi trường

- JDK 25 (`java -version` phải là 25).
- PostgreSQL 18 + extension `pgvector` 0.8.7 đang chạy trên `localhost:5432` (với các database `lawrag`, `lawrag_test`, `lawrag_failfast`, user `lawrag`).
- Port `5432` (PostgreSQL) và `8080` (App) sẵn sàng.
- Không cần cài Maven: Maven Wrapper tự tải Maven 3.9.16 ở lần chạy đầu.

## 4. Chạy nhanh

### 4.1. PowerShell (Windows)

```powershell
# Chạy ứng dụng với profile local (mvnw tự tải Maven nếu cần)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Hoặc dùng script tiện lợi (kiểm tra PostgreSQL local rồi chạy app):

```powershell
.\scripts\run-local.ps1
```

Mở:

- Chat: <http://localhost:8080/chat>
- Health: <http://localhost:8080/actuator/health>

### 4.2. macOS / Linux

```bash
chmod +x mvnw scripts/run-local.sh   # nếu checkout làm mất quyền thực thi
./scripts/run-local.sh
```

## 5. Biến môi trường và profile

Sao chép `.env.example` để tham khảo các biến môi trường. File `.env` đã bị
`.gitignore`; không commit khóa thật.

> Ứng dụng Spring Boot đọc biến môi trường của process.
> Copy `.env` không tự nạp biến vào app. Hãy export biến trong shell khi cần.

Các biến chính:

| Biến | Ý nghĩa | Mặc định local |
| --- | --- | --- |
| `DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/lawrag` |
| `DB_USERNAME` | DB user | `lawrag` |
| `DB_PASSWORD` | DB password dev | `lawrag_local_dev` |
| `GOOGLE_API_KEY` | Gemini API key (chỉ khi bật Gemini) | rỗng |
| `GEMINI_ENABLED` | Bật adapter Gemini | `false` |
| `WEBSEARCH_ENABLED` | Cờ tìm Web | `false` |
| `INGESTION_ENABLED` | Cờ nạp dữ liệu | `false` |
| `SECURE_COOKIES` | Thêm `Secure` cho cookie | `false` |
| `SERVER_PORT` | Port HTTP | `8080` |

### Profile

| Profile | Dùng khi | DB | Ghi chú |
| --- | --- | --- | --- |
| `local` (mặc định) | Phát triển máy cá nhân | `localhost:5432`, defaults | Cookie không `Secure`; Thymeleaf cache tắt |
| `test` | Test tự động | `localhost:5432/lawrag_test` (`lawrag_failfast`) | Feature flags tắt; tự reset schema `public` trước khi chạy |
| `staging` | Triển khai thử HTTPS | Bắt buộc `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Cookie `Secure`; session cookie `Secure`; không có default DB |

Ví dụ staging (không đưa giá trị thật vào repo):

```powershell
$env:DB_URL = "jdbc:postgresql://db-host:5432/lawrag"
$env:DB_USERNAME = "lawrag"
$env:DB_PASSWORD = "<secret>"
$env:GOOGLE_API_KEY = "<secret>"
$env:GEMINI_ENABLED = "true"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=staging"
```

### Fail-fast khi cấu hình sai

- `GeminiProperties` validate `model-id`, `embedding-model-id`, `embedding-space-id`,
  timeout, API key khi `app.gemini.enabled=true`, và **bắt buộc số chiều = 768**.
- `StartupValidator` chạy sau Flyway: kiểm tra extension `vector`; nếu bảng
  `article_embeddings` đã tồn tại (từ Phase 2), kiểm tra cột `embedding` là
  `vector(768)` khớp cấu hình.
- Profile staging không có giá trị mặc định cho DB, nên thiếu biến sẽ dừng khởi động.

## 6. Cấu trúc module

```text
src/main/java/vn/edu/hsf302/lawrag/
├── admin/        # Admin console (/admin/**), duyệt tài liệu & quản lý user
├── auth/         # Đăng ký, đăng nhập, UserDetails & vai trò người dùng
├── chat/         # ChatController/ChatApiController, vòng đời message, consent HTMX
├── config/       # Startup validation, exception handling, model advice, time config
├── forum/        # Diễn đàn thảo luận pháp luật (/forum/**)
├── gemini/       # EmbeddingClient, GroundingClient (HTTP + deterministic doubles)
├── ingestion/    # DocumentFetcher (chống SSRF), parser cấu trúc, chunker, pipeline nạp
├── legaldata/    # Kho văn bản pháp luật, source snapshots, bài viết/điều khoản
├── observability/# Request ID / MDC, theo dõi chi phí & ngân sách ngày
├── quota/        # Quota reservation, ledger & recovery service
├── retrieval/    # Hybrid retrieval (FTS + pgvector RRF), QueryAnalyzer, ContextGrader, CitationValidator
├── security/     # SecurityConfig, anonymous identity, rate limit, access denied handler
└── websearch/    # WebSearchWorker bền vững, provider allowlist & poller
```

13 module nghiệp vụ nằm ở các package tương ứng; mỗi package có `package-info.java`
mô tả ranh giới. Controller không phụ thuộc SDK bên ngoài.

### Adapter boundary (thay được bằng test double)

| Interface | Package | Vai trò |
| --- | --- | --- |
| `EmbeddingClient` | `gemini` | Sinh embedding tài liệu/query (`GeminiHttpEmbeddingClient` / `DeterministicEmbeddingClient`) |
| `GroundingClient` | `gemini` | Sinh/đối chiếu câu trả lời có cấu trúc (`GeminiHttpGroundingClient` / `DeterministicGroundingClient`) |
| `WebSearchProvider` | `websearch` | Chỉ phát hiện URL ứng viên trên nguồn allowlist (`VanBanChinhPhuWebSearchProvider`) |
| `DocumentFetcher` | `ingestion` | Tải nội dung nguồn sau kiểm tra allowlist/SSRF (`HttpDocumentFetcher`) |

## 7. Bảo mật và vận hành

### Phân quyền

- **Công khai:** `/`, `/chat/**`, `/api/chat/**`, `/legal/**`, `/login`, `/register`, `GET /forum`, `GET /forum/*`, `/actuator/health`, `/actuator/health/**`, `/css/**`, `/js/**`, `/images/**`, `/favicon.ico`, `/error`.
- **Đã đăng nhập:** `/forum/**` (tạo bài viết, bình luận).
- **Quản trị (`ROLE_ADMIN`):** `/admin/**`.
- Các đường dẫn khác bị `denyAll`. `UserDetailsServiceAutoConfiguration` được loại trừ để tránh tự sinh mật khẩu mặc định trong log.

### CSRF

- CSRF bật cho mọi POST/PUT/PATCH/DELETE.
- Form Thymeleaf (`th:action`, `method="post"`) tự có hidden `_csrf`.
- Trang chat có meta `_csrf` / `_csrf_header`; `static/js/app.js` gắn token vào
  header mọi request HTMX (`htmx:configRequest`), không dùng inline script.
- CSP `default-src 'self'` và các chỉ thị liên quan được cấu hình trong
  `SecurityConfig`; asset HTMX nằm trong repo, không tải CDN.

### Cookie

| Cookie | Nội dung | Thuộc tính |
| --- | --- | --- |
| `LAWRAG_SESSION` | Session Spring Security | `HttpOnly`, `SameSite=Lax`; `Secure` ở staging/HTTPS |
| `anon_id` | UUID v4 khách ẩn danh, 90 ngày | `HttpOnly`, `SameSite=Lax`; `Secure` khi bật |

Session chỉ theo cookie (`server.servlet.session.tracking-modes=cookie`), không đưa
session ID vào URL.

UI chỉ hiển thị 8 ký tự đầu của `anon_id`; không lộ giá trị đầy đủ. Xóa cookie có
thể tạo danh tính mới — giới hạn đã biết của quota ẩn danh (Phase 7).

### Request ID

`RequestIdFilter` nhận `X-Request-Id` nếu khớp `[A-Za-z0-9._-]{8,64}`, ngược lại
tạo UUID. Giá trị nằm trong MDC (`requestId`), log pattern `rid=...` và response
header. Worker ở phase sau phải copy MDC sang thread xử lý job.

### Xử lý lỗi

`GlobalExceptionHandler` trả fragment HTML cho request có header `HX-Request`, hoặc
trang `error.html`. Lỗi từ filter Spring Security (CSRF/không đủ quyền) có
`HtmxAwareAccessDeniedHandler` riêng. Không trả stack trace/exception message cho
client.

## 8. Database và migration

```bash
# Kiểm tra extension sau khi app khởi động lần đầu
psql -U lawrag -h 127.0.0.1 -p 5432 -d lawrag \
  -c "select extname, extversion from pg_extension where extname = 'vector';"
```

- Flyway bật ở mọi profile; migration nằm trong `src/main/resources/db/migration`.
- `V1__enable_pgvector.sql` chạy `CREATE EXTENSION IF NOT EXISTS vector;` — đây là
  bước xác nhận Flyway dùng đúng database có pgvector, không dùng H2.
- `spring.jpa.hibernate.ddl-auto=validate`; schema do Flyway quản lý.

Kết nối IDE: `jdbc:postgresql://localhost:5432/lawrag`, user `lawrag`,
password `lawrag_local_dev` (chỉ local).

## 9. Kiểm thử và CI

```bash
.\mvnw.cmd clean test    # Windows
./mvnw clean test        # macOS/Linux
```

- Test tích hợp kết nối trực tiếp PostgreSQL 18 + pgvector local (`lawrag_test` và `lawrag_failfast`), có thể ghi đè bằng `TEST_DB_URL`, `TEST_FAILFAST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`.
- Không dùng H2; FTS/vector chỉ được kiểm tra trên PostgreSQL thật.

Các test chính:

| Test | Nội dung |
| --- | --- |
| `G1AcceptanceTest` – `G8AcceptanceTest` | Kiểm thử chấp nhận end-to-end theo từng gate (hệ thống, schema/quota, corpus, retrieval, Gemini/citation, web worker/admin, concurrency/budget, evaluation/recovery) |
| `ForumAndAuthAcceptanceTest` | Đăng ký, đăng nhập, phân quyền và diễn đàn `/forum` |
| `RagEvaluationHarnessTest` | Đánh giá bộ 120 câu hỏi (`src/main/resources/eval/eval-120-questions.json`) |
| `StartupFailFastTest` | Sai dimension hoặc bật Gemini thiếu key đều dừng khởi động |
| `SecretHygieneTest` | Không có Google API key trong repo; `.env.example` trống khóa; `toString` che key |

## 10. Script tiện lợi

| File | Việc làm |
| --- | --- |
| `scripts/run-local.ps1` | Kiểm tra PostgreSQL local trên port 5432 rồi chạy app profile local |
| `scripts/run-local.sh` | Tương đương cho bash |

## 11. Xử lý sự cố

| Hiện tượng | Kiểm tra |
| --- | --- |
| `Connection refused` khi app start | Kiểm tra service PostgreSQL đang chạy và nghe port `5432` |
| Flyway báo không có database plugin | Phải có cả `flyway-core` và `flyway-database-postgresql` (đã khai báo) |
| `extension "vector" is not available` | Cài `vector.dll` vào `lib/` và `vector.control` + `vector--*.sql` vào `share/extension/` của PostgreSQL 18 |
| Trang chat báo `Hệ thống: DOWN` | DB/migration/health indicator; xem log có `rid=` tương ứng |
| Log Flyway báo PostgreSQL 18 “newer than this version” | Flyway 11.7.2 do Spring Boot 3.5.16 quản lý và đã migrate thành công trên PostgreSQL 18.6 + pgvector 0.8.7; cảnh báo không chặn migration. |

Tài liệu liên quan: [chính sách nguồn](docs/source-policy.md),
[quyết định kỹ thuật](docs/decisions.md),
[data dictionary](docs/data-dictionary.md),
[giao thức đánh giá](docs/evaluation-protocol.md).
