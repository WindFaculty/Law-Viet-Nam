# Quyết định kỹ thuật và phạm vi MVP

**Ngày chốt:** 12/11/2026  
**Trạng thái:** Đã chốt cho MVP, trừ các mục trong bảng quyết định mở.  
**Phạm vi:** Phase 0 — Chốt phạm vi và chứng minh các phụ thuộc.  
**Tài liệu liên quan:** [kế hoạch triển khai](ke-hoach-trien-khai-hitl-rag-phap-luat.md), [chính sách nguồn](source-policy.md), [giao thức đánh giá](evaluation-protocol.md), [báo cáo khả thi Phase 0](reports/phase-0-feasibility.md).

Tài liệu này ghi lại các quyết định đã khóa để Phase 1 có thể bắt đầu. Các kết quả đo thực tế nằm trong báo cáo khả thi; tài liệu này không thay thế bằng chứng chạy thử.

## 1. Phạm vi lĩnh vực và loại câu hỏi

### 1.1. Lĩnh vực MVP

MVP chỉ phục vụ **pháp luật Việt Nam về trật tự, an toàn giao thông đường bộ**. Câu trả lời dùng tiếng Việt, trích nguyên văn từ văn bản nguồn đã kiểm tra và ghi rõ Điều/Khoản/Điểm.

**Loại câu hỏi được hỗ trợ trong MVP:**

- Quy tắc giao thông đường bộ: làn đường, bên đi, tốc độ, khoảng cách, vượt xe, chuyển hướng, dừng/đỗ, đèn, còi, nhường đường.
- Quyền và nghĩa vụ của người tham gia giao thông; người đi bộ; người đi xe đạp, xe đạp máy, xe mô tô, xe gắn máy; người được chở.
- Điều kiện của phương tiện và người điều khiển; giấy tờ phải mang theo; giấy phép lái xe; độ tuổi, sức khỏe ở mức văn bản quy định.
- Hiệu lực, thời điểm áp dụng, sửa đổi, bãi bỏ, quy định chuyển tiếp.
- Câu hỏi tình huống đơn giản nếu đủ dữ kiện để xác định đối tượng, hành vi và ngày áp dụng.

**Không hỗ trợ trong MVP:**

- Mức phạt tiền, số điểm trừ, thời hạn tước giấy phép cụ thể **trừ khi** văn bản quy định mức phạt đã ở trạng thái `READY` và có text layer kiểm chứng. Nếu chưa có, hệ thống phải chờ xác nhận tìm nguồn, không tự suy đoán.
- Kết luận trách nhiệm dân sự, hình sự, bồi thường, tranh chấp, tư vấn vụ việc cụ thể.
- OCR văn bản scan; crawl toàn Internet; tự hợp nhất văn bản; tự suy luận toàn bộ quan hệ sửa đổi.
- Lĩnh vực ngoài giao thông đường bộ (hôn nhân, thuế, lao động, đất đai, hình sự…).

### 1.2. Cách hiểu thời điểm áp dụng

1. Nếu người dùng nêu ngày áp dụng, dùng đúng ngày đó (so sánh theo ngày, múi giờ `Asia/Ho_Chi_Minh`).
2. Nếu không nêu, dùng ngày hiện tại theo `Asia/Ho_Chi_Minh`.
3. Nếu ngày mơ hồ (“năm ngoái”, “trước khi luật mới có hiệu lực”) mà không xác định được mốc, trả `NEEDS_CLARIFICATION`.
4. Với câu hỏi lịch sử, vẫn xét văn bản cũ nếu còn hiệu lực tại ngày áp dụng; không loại chỉ vì văn bản đã hết hiệu lực hiện tại.
5. Nếu không xác định được phiên bản/hiệu lực ở cấp điều khoản, không được khẳng định “đang có hiệu lực”; trả `NEED_WEB_SEARCH` hoặc `NEEDS_CLARIFICATION` theo trường hợp.

### 1.3. Định nghĩa câu trả lời thành công

Một lượt chỉ được ghi nhận khi đồng thời:

- Có câu trả lời đã lưu bền vững, đọc lại được trong phiên.
- Có ít nhất một citation trỏ tới Điều/Khoản đã chọn và citation vượt qua validator.
- Phần hiển thị dưới nhãn “trích nguyên văn” khớp snapshot nguồn đã lưu.
- Trạng thái tin nhắn là `COMPLETED` và ledger quota được ghi đúng một lần.

Không tính là thành công: `NEEDS_CLARIFICATION`, `CANCELLED`, `EXPIRED`, `NO_EVIDENCE`, `WEB_FAILED`, `FAILED`, câu trả lời ngoài phạm vi.

## 2. Chính sách khách ẩn danh và quota

### 2.1. Danh tính khách

- Server tạo mã khách ẩn danh ngẫu nhiên (UUID v4) và đặt trong cookie `anon_id`.
- Cookie: `HttpOnly`, `SameSite=Lax`, `Secure` khi chạy HTTPS; thời hạn 90 ngày.
- Không dùng email, số điện thoại hoặc thông tin định danh khác trong MVP.
- Xóa cookie có thể tạo danh tính mới; đây là giới hạn đã biết của quota ẩn danh. Giới hạn theo IP và trần toàn hệ thống chỉ bổ sung chống lạm dụng, không thay thế danh tính.
- Tài khoản người dùng cuối chưa có trong MVP. Tài khoản quản trị dùng cơ chế đăng nhập riêng và không bị trừ quota khách.

### 2.2. Quota và thời điểm đặt lại

| Quy tắc | Giá trị khóa |
| --- | --- |
| Số lượt trả lời thành công / khách / ngày | **5** |
| Múi giờ tính ngày | `Asia/Ho_Chi_Minh` |
| Thời điểm đặt lại | **00:00** giờ Việt Nam mỗi ngày; khóa kỳ là `yyyy-MM-dd` |
| Số job tìm Web đã xác nhận / khách / ngày | **3** (kể cả job thất bại) |
| Số job tìm Web đồng thời / khách | **1** |
| Số câu hỏi / IP / giờ | **20** |
| Khoảng cách tối thiểu giữa hai câu hỏi | **5 giây** |
| Poll trạng thái | tối đa **60 lần/phút/phiên** |

**Cách tính:**

- Chỉ trả lời thành công mới chuyển reservation thành “đã dùng” và ghi ledger.
- Khi chờ xác nhận, cần hỏi thêm, ngoài phạm vi, từ chối, hết hạn, không đủ căn cứ hoặc lỗi: giải phóng reservation, không trừ lượt.
- Đồng ý tìm Web: giữ chỗ mới nếu còn lượt; nếu hết lượt thì không tạo job.
- Retry, poll, reload, mở nhiều tab hoặc gửi lại cùng request không được trừ thêm.
- Ledger có unique key theo tin nhắn trả lời để chốt quota đúng một lần; reservation và ledger là hai bản ghi tách biệt.
- Khi hết quota: trả lỗi có hướng dẫn, nêu thời điểm đặt lại; **không gọi Gemini, không gọi tìm kiếm, không tạo job**. Không xếp hàng câu hỏi để xử lý sau 00:00.
- Nếu người dùng còn 0 lượt, không cho gửi câu hỏi mới; không dựa vào việc “có thể thành công” để xử lý miễn phí.

Quota thành công và giới hạn tài nguyên là hai lớp độc lập: câu hỏi bị từ chối hoặc không đủ căn cứ không trừ lượt nhưng vẫn tiêu tốn embedding, token đánh giá và băng thông; các trần trong mục 6 vẫn áp dụng.

## 3. Phiên bản công nghệ đã khóa

| Thành phần | Phiên bản khóa | Ghi chú / bằng chứng |
| --- | --- | --- |
| Java | **25 LTS** | Máy dev: Oracle JDK `25.0.4.1`; `JAVA_HOME=C:\Program Files\Java\jdk-25.0.4.1`. Spring Boot 3.5.16 công bố hỗ trợ Java 17–25. Không tự nâng lên bản mới hơn. |
| Maven | **3.9.16** | `mvn -version` chạy trên JDK 25. Phase 1 thêm Maven Wrapper cùng phiên bản. |
| Gradle | Không dùng | Không có trong môi trường; không thêm để tránh hai build tool. |
| Spring Boot | **3.5.16** | Bản 3.x mới nhất tại ngày chốt; yêu cầu Maven 3.6.3+. |
| Spring Framework | **6.2.19** | Do Spring Boot 3.5.16 quản lý. |
| Embedded Tomcat | **10.1.55** | Do Spring Boot 3.5.16 quản lý. |
| Thymeleaf | **3.1.5.RELEASE** | Do Spring Boot 3.5.16 quản lý. |
| HTMX | **2.0.11** | Vendor file JS trong repo; không tải CDN lúc chạy. |
| PostgreSQL | **18.6** | Local có service 18.6; dùng Docker để có pgvector. |
| pgvector | **0.8.7** | Image `pgvector/pgvector:pg18`, digest `sha256:c29f30688a9ad961691bdf0ec1650717cc132a5814619d80f80463d6e4c42e47`. |
| Docker Engine | **29.7.2** | Docker Desktop trên Windows; Docker Compose `v5.4.0`. |
| PostgreSQL JDBC | **42.7.11** | Do Spring Boot 3.5.16 quản lý. |
| Flyway | **11.7.2** + `flyway-database-postgresql` **11.7.2** | Bắt buộc có plugin database; đã thử migrate thành công với PostgreSQL 18.6 + pgvector 0.8.7. |
| Truy cập vector | Spring JDBC + `PGobject` kiểu `vector` | Không dùng ORM để map vector trong MVP; tránh che giấu câu SQL và kiểu dữ liệu. |
| Kiểm thử tích hợp | Testcontainers (phiên bản do Spring Boot quản lý) | Dùng PostgreSQL + pgvector thật; không dùng H2 thay thế. |
| Múi giờ ứng dụng | `Asia/Ho_Chi_Minh` | Dùng cho quota, ngày áp dụng, hiển thị. |
| Encoding | UTF-8 | Bắt buộc cho tiếng Việt, số hiệu và trích dẫn. |

**Quyết định Java:** chọn JDK 25 LTS đang cài thay vì Java 21 vì Spring Boot 3.5.16 hỗ trợ chính thức đến Java 25. Nếu CI hoặc thư viện phát sinh lỗi chỉ có trên Java 25, phải mở quyết định mới và ghi rõ phiên bản thay thế; không âm thầm hạ cấp.

**Quyết định PostgreSQL:** dùng Docker `pgvector/pgvector:pg18`; không dùng service PostgreSQL 18.6 cài sẵn trên Windows vì chưa có extension `vector`. Không cài pgvector vào service hệ thống trong MVP để tránh thay đổi môi trường ngoài repo.

## 4. Gemini: model, embedding và hợp đồng đầu ra

### 4.1. Model sinh JSON / đánh giá

| Thuộc tính | Giá trị khóa |
| --- | --- |
| Model ID | `gemini-2.5-flash` (bản stable) |
| API | `v1beta` (`generativelanguage.googleapis.com`) |
| Mục đích | Phân loại đủ căn cứ, chọn Article ID, sinh câu hỏi làm rõ/gợi ý query, diễn giải có nhãn |
| Định dạng | `responseMimeType=application/json`, `responseSchema` theo enum của ứng dụng |
| Temperature | `0` cho grading/classification |
| Timeout mỗi lần gọi | **30 giây** |
| Số lần thử | **1 lần chính + 1 lần retry** chỉ với 429/5xx/timeout; không retry lỗi schema |
| Giới hạn output | tối đa **2.048 token** cho grading/answer ngắn |
| Giới hạn input đã kiểm chứng | 1.048.576 token (model); ứng dụng tự giới hạn context 12.000 token |
| Tool | Tắt Google Search/URL grounding trong luồng local; không để model tự gọi mạng trước xác nhận |

`gemini-2.5-flash` đã được gọi thử với JSON schema thật: trả `{"decision":"NEED_WEB_SEARCH","reasonCode":"NO_MATCH","selectedArticleIds":[]}`, HTTP 200, 2.333 ms, 73 prompt token, 26 candidate token. Đây là bằng chứng một lần chạy, không phải cam kết độ trễ.

### 4.2. Model embedding

| Thuộc tính | Giá trị khóa |
| --- | --- |
| Model ID | `gemini-embedding-001` |
| Số chiều | **768** (`outputDimensionality=768`) |
| Không gian vector | `gemini-embedding-001@768:v1` |
| Task type | `RETRIEVAL_DOCUMENT` cho tài liệu; `RETRIEVAL_QUERY` cho câu hỏi |
| Timeout | **30 giây** mỗi request; batch cho ingestion |
| Giới hạn input | 2.048 token / đoạn (theo model) |
| Chuẩn hóa | Lưu vector thô; dùng cosine distance `<=>` của pgvector; không tự chuẩn hóa lại khác nguồn |
| Model cũ | `text-embedding-004` **không dùng**: trả 404 trên cả `v1` và `v1beta` tại ngày kiểm tra |

Đã đo: 768 chiều, L2 norm ≈ 0,577953 (vector không chuẩn hóa sẵn), cosine query “mũ bảo hiểm xe máy” với đoạn liên quan **0,8077**, với đoạn không liên quan **0,6106**. Con số này chỉ là kiểm tra khả thi, chưa phải hiệu chỉnh threshold.

**Quy tắc bất biến:** mỗi vector phải lưu `model_id`, `embedding_space_id`, `dimensions`, `text_hash`. Không so sánh vector khác không gian. Khi đổi model hoặc số chiều, tạo không gian mới và tái tạo chỉ mục; không ghi đè lẫn lộn.

### 4.3. Hợp đồng đầu ra logic

```json
{
  "decision": "ANSWER | NEED_WEB_SEARCH | NEED_CLARIFICATION",
  "selectedArticleIds": [],
  "reasonCode": "SUFFICIENT | NO_MATCH | WRONG_SCOPE | TEMPORAL_UNCERTAINTY | MISSING_FACTS",
  "suggestedSearchQuery": null,
  "clarificationQuestion": null
}
```

Backend kiểm tra lại: enum hợp lệ; `ANSWER` phải có ID nằm trong context; ID phải thuộc nguồn đã `READY`; metadata phải phù hợp ngày áp dụng; không chấp nhận URL/ID do model tự tạo. Output sai schema hoặc provider lỗi không được chuyển thành câu trả lời thành công.

### 4.4. Chuẩn bị query và document

- Giữ dấu tiếng Việt, số hiệu, Điều/Khoản/Điểm; chỉ chuẩn hóa khoảng trắng và ký tự điều khiển.
- Document chunk mang tiêu đề văn bản + số hiệu + Điều/Khoản + ngày hiệu lực; không cắt rời khỏi ngữ cảnh pháp lý.
- Query mang ngày áp dụng và lĩnh vực; không gửi toàn bộ lịch sử chat.
- Với câu hỏi tra số hiệu/Điều/Khoản, ưu tiên nhánh lexical trước; không bắt embedding phải hiểu đầy đủ tiếng Việt.

## 5. Chiến lược `WebSearchProvider`

### 5.1. Provider khóa cho MVP

**`VanBanChinhPhuSearchProvider`** — adapter gọi cơ chế tìm kiếm công khai của `vanban.chinhphu.vn` (form search của cổng văn bản Chính phủ), trả danh sách URL ứng viên thuộc allowlist.

- Đã chứng minh: tìm “Giao thông đường bộ” trả Luật 36/2024/QH15; tìm “168/2024” trả Nghị định 168/2024/NĐ-CP và Nghị định 238/2026/NĐ-CP; hai request mất khoảng 3,4 giây.
- Chỉ dùng để **phát hiện URL**, không dùng snippet làm căn cứ pháp luật.
- Kết quả tối đa 10; chỉ tải tối đa 5 URL/job.
- Hạn chế: form search là ASP.NET WebForms cần `__VIEWSTATE`/`__EVENTVALIDATION`; cấu trúc có thể đổi. Phải có golden test, health check và fallback thủ công; lỗi provider không được âm thầm đổi sang nguồn ngoài allowlist.
- Provider này chỉ tìm trong cổng văn bản Chính phủ, không phải tìm kiếm toàn Internet. UI phải ghi đúng: “tìm trên cổng văn bản Chính phủ” khi provider này đang bật.

### 5.2. Phương án dự phòng

- **Google Programmable Search / Custom Search JSON API**: chỉ bật nếu nhóm có `GOOGLE_SEARCH_API_KEY` và `GOOGLE_SEARCH_ENGINE_ID`; chưa có trong môi trường hiện tại. Đây là quyết định mở.
- **Nhập URL thủ công bởi quản trị**: chỉ dùng cho kiểm thử/khắc phục sự cố; không tính là tính năng tìm Web hoàn chỉnh và không được tự động hóa trước xác nhận.

### 5.3. Phương án bị loại trong MVP

- **Gemini Google Search grounding làm provider chính**: một lần thử với `gemini-2.5-flash` + `google_search` timeout sau 90 giây, không có kết quả; chưa đủ bằng chứng về độ trễ, chi phí và điều khoản trích dẫn. Không dùng làm provider cho đến khi có thử nghiệm mới.
- **Scrape Bing/DuckDuckGo HTML**: không có API/permission ổn định, dễ vi phạm điều khoản và cấu trúc thay đổi; không dùng.
- **Crawl toàn Internet hoặc mở rộng nguồn tự động**: ngoài phạm vi MVP.

### 5.4. Quy tắc gọi provider

- Chỉ gọi sau khi có consent hợp lệ, còn hạn, đúng session/message/question hash.
- Query đã lưu ở server; không tin query/URL từ trình duyệt.
- Loại dữ liệu cá nhân không cần thiết trước khi gửi ra ngoài; không gửi toàn bộ lịch sử chat.
- Ghi log: query, provider, thời điểm, thứ hạng, URL, lý do loại; không ghi secret.
- Mọi URL phải qua allowlist + kiểm tra SSRF + kiểm tra redirect trước khi tải.

## 6. Xác nhận, job và ngân sách tài nguyên

### 6.1. Thời hạn xác nhận

| Quy tắc | Giá trị khóa |
| --- | --- |
| Thời hạn consent | **15 phút** kể từ khi tin nhắn vào `AWAITING_WEB_CONFIRM` |
| Gắn kết | session ID + message ID + hash câu hỏi + thời hạn |
| Dùng lại | Mỗi consent chỉ dùng một lần; consent cũ không áp dụng cho câu hỏi khác |
| Hết hạn | Chuyển `EXPIRED`; không tạo job; không trừ lượt |
| Đồng ý lặp | Trả trạng thái/job hiện có; không tạo job thứ hai |
| Đồng ý và từ chối đồng thời | Chỉ một chuyển trạng thái thắng; transaction + điều kiện trạng thái |

### 6.2. Giới hạn job

| Tham số | Giá trị khóa |
| --- | --- |
| Thời gian toàn job | **120 giây** |
| Số URL tối đa / job | **5** |
| Số job đồng thời toàn hệ thống | **2** |
| Số lần thử / job | **2** tổng cộng cho lỗi tạm thời |
| Lease | 60 giây; heartbeat 20 giây |
| Redirect tối đa | 3 |
| Kích thước tối đa / URL | 15 MB |
| Thời gian tải tối đa / URL | 30 giây (kết nối 5 giây) |
| Số kết quả search tối đa | 10 |
| Stage | `CLAIM → SEARCH → FETCH → SNAPSHOT → PARSE → VALIDATE → EMBED → PUBLISH → RETRIEVE_AGAIN → FINALIZE` |
| Sau nạp | Chỉ đánh giá lại một lần; nếu vẫn thiếu căn cứ → `NO_EVIDENCE`; không tự mở job mới |

### 6.3. Ngân sách retrieval và token

| Tham số | Giá trị khóa |
| --- | --- |
| Ứng viên mỗi nhánh retrieval | 20 |
| Hợp nhất | Reciprocal Rank Fusion |
| Số chunk đưa vào context | 5–8 |
| Context tối đa | 12.000 token |
| Output tối đa | 2.048 token |
| Embedding chunk mới / job | 100 |
| Embedding chunk / ngày | 2.000 |
| GenerateContent / ngày | 200 |
| Web job / ngày toàn hệ thống | 20 |

Các trần này là **giới hạn tài nguyên**, không phải cam kết chi phí bằng USD. Chưa có quyền truy cập billing/usage để xác nhận hạn mức và giá; xem quyết định mở OD-02. Khi đạt 70% trần ngày, ghi cảnh báo; đạt 100% thì tắt feature flag tương ứng và trả thông báo tạm thời không khả dụng, không xóa dữ liệu hội thoại.

## 7. Nguồn, độ mới và dữ liệu

- Allowlist và quy tắc kiểm tra nguồn: xem [source-policy.md](source-policy.md). Chỉ các host được liệt kê mới được tải.
- MVP ưu tiên văn bản có **text layer** (PDF sinh từ Word hoặc HTML có text). PDF scan không được OCR trong MVP; đưa vào trạng thái từ chối/ cách ly và chuyển người kiểm tra.
- Văn bản Luật 36/2024/QH15 đã kiểm tra: hai PDF Công báo có text layer, tổng 82 trang, 89 Điều; dùng làm mẫu parser.
- Nghị định 168/2024/NĐ-CP và Nghị định 238/2026/NĐ-CP có PDF scan (không có text layer) tại thời điểm kiểm tra; không được tự động `READY` nếu chưa có bản text hoặc người kiểm tra xử lý.
- Độ mới: `lastVerifiedAt` tối đa **30 ngày** cho câu trả lời khẳng định tình trạng hiện hành. Quá hạn → `STALE`; không khẳng định “đang có hiệu lực”, phải kiểm tra lại hoặc chờ xác nhận tìm nguồn.
- Ngày hiệu lực phải lấy từ văn bản hoặc metadata chính thức, không lấy từ tên file, `Last-Modified`, ngày tải hoặc suy luận của AI.
- Hiệu lực ở cấp điều khoản được lưu riêng. Ví dụ đã kiểm tra: Luật 36/2024/QH15 có hiệu lực 01/01/2025, nhưng khoản 3 Điều 10 có hiệu lực 01/01/2026 (Điều 88 khoản 2).

## 8. Bảo mật, quyền riêng tư và vận hành

- Secret chỉ đọc từ biến môi trường; không commit, không ghi log, không trả về client. Biến chuẩn: `GOOGLE_API_KEY`. Không dùng alias `GEMINI_API_KEY` để tránh hai nguồn cấu hình.
- CSRF bật cho mọi POST; cookie phiên `HttpOnly`, `SameSite`, `Secure` khi HTTPS.
- Nội dung nguồn là dữ liệu không tin cậy: không chạy script, không thay đổi prompt/system rule, không cấp quyền công cụ.
- Không gửi toàn bộ lịch sử chat hoặc dữ liệu cá nhân không cần thiết cho provider.
- Trang quản trị tách quyền; mọi thao tác duyệt dữ liệu phải ghi người thực hiện, thời gian, lý do.
- Feature flag tối thiểu: `websearch.enabled`, `ingestion.enabled`, `gemini.enabled`; mặc định tắt trong test, bật có kiểm soát ở local/staging.

## 9. Bảng quyết định còn mở

| ID | Quyết định cần chốt | Người chịu trách nhiệm | Hạn | Ảnh hưởng nếu chưa chốt |
| --- | --- | --- | --- | --- |
| OD-01 | Chỉ định người kiểm tra dữ liệu pháp luật có tên, chuyên môn và cam kết thời gian | Nhóm trưởng (A) | 13/11/2026 | Không được coi seed/corpus là đã kiểm chứng pháp lý; G0 chỉ đạt có điều kiện |
| OD-02 | Xác nhận quyền billing, hạn mức và ngân sách Gemini; đặt cảnh báo chi phí | A | 13/11/2026 | Không thể cam kết chi phí/ngân sách; chỉ có trần cuộc gọi |
| OD-03 | Chọn provider tìm kiếm production: site-native `vanban.chinhphu.vn` hay Custom Search JSON API | B | 14/11/2026 | Chưa có tìm kiếm tổng quát; chỉ có adapter nguồn |
| OD-04 | Quyết định OCR cho PDF scan (Nghị định 168/2024, 238/2026) hay loại khỏi corpus tự động | B + người kiểm tra pháp luật | 14/11/2026 | Câu hỏi mức phạt sẽ luôn chờ Web/không trả lời |
| OD-05 | Cách lấy toàn văn `vbpl.vn` (API/JS render hay snapshot thủ công) | B | 15/11/2026 | `vbpl.vn` chỉ dùng metadata, không dùng làm nguồn text |
| OD-06 | Chốt số liệu quota 5 lượt/ngày và 3 job Web/ngày với người đặt yêu cầu | C | 13/11/2026 | Không thể khóa UI/error message và kiểm thử tải |
| OD-07 | Chọn đường triển khai PostgreSQL: Docker Compose bắt buộc hay cài pgvector native | A | 13/11/2026 | Phase 1 có thể bị chặn nếu Docker không chạy |
| OD-08 | Chính sách lưu trữ hội thoại/PII và thời hạn xóa | A | 14/11/2026 | Chưa thể cam kết quyền riêng tư/retention |
| OD-09 | Có tài khoản người dùng cuối trong MVP hay chỉ khách + admin | A | 13/11/2026 | Ảnh hưởng schema, quota và UI |
| OD-10 | Hoàn tất 120 câu và ký duyệt bộ giữ lại; mở rộng seed | C + người kiểm tra pháp luật | 20/11/2026 | Không đủ bộ đánh giá G8 |
| OD-11 | Chốt hosting staging, HTTPS, backup/restore | C | 25/11/2026 | Không đạt G9 |
| OD-12 | Chốt CSP và cách vendor HTMX/asset | C | 16/11/2026 | Rủi ro XSS/asset ngoài |

Tên cá nhân được điền khi nhóm chốt; bảng hiện dùng vai trò A/B/C theo kế hoạch để không tự gán người chưa được phân công.

## 10. Trạng thái G0

**G0 = ĐẠT CÓ ĐIỀU KIỆN (CONDITIONAL PASS).** Bốn điều kiện kỹ thuật của G0 đều đã đạt; “có điều kiện” áp dụng cho việc tuyên bố tính năng tìm Web hoàn tất và cho việc coi dữ liệu là đã kiểm chứng pháp lý, không phải cho việc bắt đầu Phase 1.

Đã chứng minh thực tế:

- Java 25 LTS + Maven 3.9.16 chạy được; Spring Boot 3.5.16 tương thích Java 25 theo tài liệu chính thức.
- Docker + PostgreSQL 18.6 + pgvector 0.8.7 chạy được; tạo extension, cosine và FTS hoạt động.
- Flyway 11.7.2 + plugin PostgreSQL migrate được vào DB pgvector.
- Gemini `gemini-2.5-flash` sinh JSON đúng schema; `gemini-embedding-001` trả vector 768 chiều.
- Provider site-native tìm được URL văn bản giao thông; tải và bóc tách được Luật 36/2024/QH15 có text layer.

Điều kiện còn lại: người kiểm tra pháp luật (OD-01), quyết định OCR (OD-04), provider production (OD-03), và xử lý `vbpl.vn` (OD-05). Phase 1 được phép bắt đầu; **không được tuyên bố tính năng tìm Web hoàn tất** cho đến khi các điều kiện trên được đóng.
