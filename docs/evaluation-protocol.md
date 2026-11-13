# Giao thức đánh giá RAG pháp luật giao thông đường bộ

**Ngày ban hành:** 12/11/2026  
**Trạng thái:** Khung đánh giá MVP; bộ seed mới ở mức “kiểm tra nguồn”, chưa có chữ ký người kiểm tra pháp luật.  
**Liên quan:** [quyết định MVP](decisions.md), [chính sách nguồn](source-policy.md), [báo cáo khả thi Phase 0](reports/phase-0-feasibility.md), [bộ câu hỏi seed](eval-seed-questions.json).

Tài liệu này định nghĩa cấu trúc bộ 120 câu, cách chia tập phát triển/tập giữ lại, nhãn kỳ vọng, thủ tục chạy và tiêu chí nghiệm thu. Đây là kế hoạch đo, không phải kết quả đã đo và không chứng minh độ chính xác cho toàn bộ pháp luật Việt Nam.

## 1. Mục tiêu và giới hạn

### 1.1. Mục tiêu

- Đo hành vi đúng của luồng HITL: trả lời local khi đủ căn cứ, chờ xác nhận khi thiếu, hỏi lại khi thiếu dữ kiện, từ chối ngoài phạm vi.
- Đo chất lượng căn cứ và tính nguyên văn, không chỉ đo “câu trả lời nghe hợp lý”.
- Đo các lỗi nghiêm trọng: tìm/tải trước xác nhận, citation sai, dùng văn bản sai thời điểm, trừ quota lặp, đọc chéo phiên.
- Tạo bằng chứng tái lập được theo phiên bản code, prompt, model, corpus và retrieval config.

### 1.2. Không phải

- Không phải chứng nhận tư vấn pháp lý.
- Không đại diện cho mọi câu hỏi giao thông đường bộ ngoài 120 câu.
- Không dùng một AI khác để thay người kiểm tra đối chiếu nguồn/ngày áp dụng.
- Không coi build thành công, lint Markdown hoặc demo giao diện là bằng chứng chất lượng RAG.

## 2. Cấu trúc bộ 120 câu

### 2.1. Phân nhóm

| Mã nhóm | Nhóm | Tổng | Dev | Holdout | Mục đích |
| --- | --- | ---: | ---: | ---: | --- |
| `C1_DIRECT_EVIDENCE` | Kho có đủ căn cứ trực tiếp | 20 | 10 | 10 | Trả lời local và trích đúng Điều/Khoản. |
| `C2_MISSING_DOCUMENT` | Thiếu văn bản cần thiết | 20 | 10 | 10 | Chuyển chờ xác nhận, không trả lời đoán. |
| `C3_NEAR_SYNONYM_WRONG_SCOPE` | Gần nghĩa nhưng sai đối tượng/phạm vi | 20 | 10 | 10 | Grader không bị đánh lừa bởi similarity. |
| `C4_TEMPORAL_VERSION` | Khác thời điểm, có sửa đổi hoặc bãi bỏ | 20 | 10 | 10 | Kiểm tra phiên bản và hiệu lực ở cấp điều khoản. |
| `C5_MISSING_FACTS` | Thiếu dữ kiện hoặc có điều kiện ngoại lệ | 20 | 10 | 10 | Kiểm tra hỏi lại và căn cứ nhiều đoạn. |
| `C6_OUT_OF_SCOPE` | Ngoài lĩnh vực MVP | 20 | 10 | 10 | Kiểm tra giới hạn phạm vi, không tự mở rộng corpus. |
| **Tổng** |  | **120** | **60** | **60** |  |

- Mỗi nhóm chia đúng **10 câu phát triển** và **10 câu giữ lại**.
- Không dùng câu holdout để chỉnh prompt/threshold. Chỉ chạy holdout sau khi khóa cấu hình.
- Mỗi câu có thể có nhiều điều kiện chấp nhận, nhưng chỉ có một `expectedDecision` chính.

### 2.2. Trường bắt buộc của một câu

| Trường | Ý nghĩa |
| --- | --- |
| `id` | ID duy nhất, ví dụ `C1-DEV-01`. |
| `category` | Mã nhóm ở trên. |
| `split` | `dev` hoặc `holdout`. |
| `question` | Câu hỏi tiếng Việt, giữ nguyên cách người dùng có thể hỏi. |
| `asOfDate` | Ngày áp dụng `yyyy-MM-dd`; nếu không nêu, dùng ngày chạy theo `Asia/Ho_Chi_Minh`. |
| `expectedDecision` | Nhãn kỳ vọng (mục 3). |
| `expectedSourceRefs` | Văn bản/Điều/Khoản đúng; rỗng nếu kỳ vọng không trả lời local. |
| `expectedAnswerPoints` | Các điểm bắt buộc phải có trong câu trả lời. |
| `forbiddenBehaviors` | Hành vi không được xảy ra (ví dụ nêu mức phạt khi chưa có nguồn). |
| `acceptanceCriteria` | Điều kiện pass cụ thể cho câu. |
| `reviewer` | Tên người kiểm tra pháp luật; `null` nếu chưa có. |
| `verificationStatus` | `source-checked-ai`, `design-verified`, `pending-legal-review`, `verified`. |
| `corpusVersion` | Phiên bản corpus dùng khi đánh giá. |
| `notes` | Ghi chú về nguồn, ngày, giới hạn. |

Với câu “thiếu nguồn”, lưu trạng thái corpus tại thời điểm đánh giá và lý do nguồn chưa `READY`.

## 3. Nhãn kỳ vọng và ánh xạ trạng thái

| Nhãn đánh giá | Hành vi kỳ vọng | Ánh xạ runtime |
| --- | --- | --- |
| `ANSWER_LOCAL` | Trả lời từ kho nội bộ, có citation hợp lệ, không gọi Web. | `COMPLETED`, có citation, trừ đúng 1 lượt. |
| `NEED_WEB_CONFIRM` | Kho chưa đủ căn cứ; hiển thị nút đồng ý/từ chối; **không** gọi search/fetch trước consent. | `AWAITING_WEB_CONFIRM`, không trừ lượt. |
| `NEED_CLARIFICATION` | Thiếu dữ kiện quyết định; hỏi lại; không gọi Web. | `NEEDS_CLARIFICATION`, không trừ lượt. |
| `OUT_OF_SCOPE` | Từ chối lịch sự vì ngoài lĩnh vực MVP; không mở rộng corpus, không gọi Web. | `COMPLETED` với `outcome=OUT_OF_SCOPE`, không citation, không trừ lượt. |
| `NO_EVIDENCE` | Sau khi đã tìm Web hợp lệ vẫn thiếu căn cứ; dừng, không tự tìm tiếp. | `NO_EVIDENCE`, không trừ lượt. |
| `CANCELLED` / `EXPIRED` | Người dùng từ chối hoặc consent hết hạn; không job. | `CANCELLED` / `EXPIRED`, không trừ lượt. |
| `FAILED` | Lỗi xử lý/provider; không trả lời khẳng định. | `WEB_FAILED` / `FAILED`, không trừ lượt. |

`OUT_OF_SCOPE` là nhãn đánh giá; không thêm trạng thái tin nhắn mới nếu không cần. Runtime dùng `outcome` để phân biệt và không ghi quota.

## 4. Định nghĩa sáu nhóm

### C1 — Kho có đủ căn cứ trực tiếp

Câu hỏi có ít nhất một Điều/Khoản trong corpus `READY` trả lời trực tiếp. Kỳ vọng `ANSWER_LOCAL` với citation đúng, nguyên văn khớp snapshot. Không chấp nhận câu trả lời đúng nội dung nhưng citation sai hoặc không có citation.

### C2 — Thiếu văn bản cần thiết

Câu hỏi cần văn bản chưa có trong corpus `READY` (ví dụ mức phạt cụ thể khi Nghị định xử phạt chỉ có bản scan/chưa duyệt). Kỳ vọng `NEED_WEB_CONFIRM`, không nêu con số, không gọi Web trước consent.

### C3 — Gần nghĩa nhưng sai đối tượng/phạm vi

Câu hỏi dùng từ gần nghĩa hoặc cùng chủ đề nhưng khác đối tượng (xe đạp vs xe đạp máy, xe mô tô vs ô tô, người đi bộ vs người lái xe). Grader phải chọn đúng phạm vi Điều/Khoản; không được áp điều khoản của đối tượng khác. Nếu không có căn cứ cho đối tượng được hỏi, phải nói rõ giới hạn thay vì suy diễn.

### C4 — Khác thời điểm, có sửa đổi hoặc bãi bỏ

Câu hỏi có `asOfDate` khác hiện tại hoặc liên quan văn bản hết hiệu lực/sửa đổi một phần. Kỳ vọng chọn đúng phiên bản theo ngày, kể cả hiệu lực ở cấp khoản. Không dùng văn bản hiện hành để trả lời câu hỏi lịch sử nếu ngày áp dụng trước ngày hiệu lực.

### C5 — Thiếu dữ kiện hoặc có điều kiện ngoại lệ

Câu hỏi thiếu loại xe, hành vi, số người, độ tuổi, ngày hoặc tình huống. Kỳ vọng `NEED_CLARIFICATION` với câu hỏi làm rõ cụ thể; không đoán và không gọi Web.

### C6 — Ngoài lĩnh vực MVP

Câu hỏi thuộc lĩnh vực khác (hôn nhân, thuế, lao động, đất đai, hình sự…). Kỳ vọng `OUT_OF_SCOPE`, nêu giới hạn, không tự mở rộng corpus, không gọi Web.

## 5. Chỉ số và mục tiêu

Các mục tiêu dưới đây lấy từ kế hoạch mục 14.2, dùng để ra quyết định MVP trong phạm vi bộ câu hỏi; không phải cam kết độ chính xác toàn ngành luật.

| Chỉ số | Công thức | Mục tiêu | Ghi chú |
| --- | --- | --- | --- |
| Tuân thủ xác nhận | Số lần search/fetch trước consent trong ca kiểm tra mạng | **0** | Cuộc gọi model/embedding ghi riêng, không tính là search. |
| Tính nguyên văn | Số quote khớp snapshot / tổng quote hiển thị | **100%** | So khớp sau chuẩn hóa khoảng trắng; không chấp nhận AI viết lại. |
| Chất lượng căn cứ | Số câu trả lời được người kiểm tra xác nhận đủ căn cứ + đúng thời điểm / số câu đã trả lời trên holdout | **≥95%** | Báo tử số/mẫu số, không chỉ tỷ lệ. |
| Khả năng trả lời khi có nguồn | Số câu C1 trả đúng / tổng câu C1 | **≥90%** | Chống cách “từ chối mọi câu để đạt điểm”. |
| Phát hiện cần tìm thêm | Số câu C2 chuyển `AWAITING_WEB_CONFIRM` đúng / số câu C2 áp dụng | **≥90%** | Không tính câu cần hỏi thêm/ngoài phạm vi vào mẫu số này. |
| Quota và idempotency | Số lần trừ lặp, quota âm, job trùng | **0** | Chạy bộ tranh chấp riêng. |
| Quyền truy cập | Số lần đọc/điều khiển chéo phiên thành công | **0** | Kiểm tra message, job, citation. |
| Độ trễ local | p95 của `ANSWER_LOCAL` | **≤10 giây** | Staging, 10 phiên đồng thời; báo cấu hình và số mẫu. |
| Phản hồi đồng ý | p95 từ POST đồng ý đến trạng thái queued | **≤1 giây** | Không gồm thời gian tìm/crawl. |
| Thời gian Web | Tỷ lệ job kết thúc hoặc timeout theo hạn | **100%** | Báo phân bố thực tế; không cam kết luôn tìm được câu trả lời. |

Mỗi báo cáo phải có:

- Tử số/mẫu số cho từng tỷ lệ.
- Phân tích theo nhóm C1–C6, không chỉ một tỷ lệ tổng.
- Danh sách lỗi cụ thể kèm ID câu, trạng thái, citation, ngày áp dụng.
- Phân biệt số đo thực với giá niêm yết/ước tính.
- Phiên bản code, prompt, model, embedding space, corpus, retrieval config.

## 6. Thủ tục chạy

### 6.1. Chuẩn bị

1. Khóa code, prompt, model ID, embedding space, corpus version, retrieval config và dataset version.
2. Chạy migration trên PostgreSQL + pgvector thật; không dùng H2.
3. Kiểm tra health check, quyền admin, CSRF, quota và feature flag.
4. Ghi `runId`, ngày giờ, người chạy, môi trường, commit hash.

### 6.2. Tập phát triển

1. Chạy 60 câu dev.
2. Ghi kết quả từng câu: quyết định, citation, độ trễ, token, lỗi.
3. Hiệu chỉnh threshold, top-K, prompt, query normalization nếu cần.
4. Ghi mỗi thay đổi và lý do; chạy lại các câu bị ảnh hưởng.
5. Không dùng holdout để chọn tham số.

### 6.3. Khóa cấu hình

- Đóng băng code/prompt/threshold/model/corpus.
- Ghi hash cấu hình và phiên bản dataset.
- Chỉ sau đó mới chạy 60 câu holdout.

### 6.4. Tập giữ lại

1. Chạy một lần với cấu hình đã khóa.
2. Người kiểm tra pháp luật đối chiếu câu trả lời với nguồn và ngày áp dụng; không chỉ hỏi AI khác.
3. Ghi mọi lỗi, kể cả lỗi nhỏ; không sửa prompt trong lúc đang chấm.
4. Nếu phải sửa prompt/corpus/model sau khi xem holdout, ghi nhận **vòng đánh giá mới**; không báo cùng một phép đánh giá chưa bị tác động.

### 6.5. Người chấm

- Tối thiểu một người kiểm tra dữ liệu pháp luật đã được chỉ định (OD-01).
- Người chấm ghi tên, ngày, nguồn đã đối chiếu, kết luận pass/fail.
- Không mặc định lập trình viên hoặc AI đã xác nhận tính đúng của dữ liệu.

## 7. Bộ câu hỏi hiện có

### 7.1. Quy mô và trạng thái

- File canonical: [`src/main/resources/eval/eval-120-questions.json`](../src/main/resources/eval/eval-120-questions.json).
- [`docs/eval-seed-questions.json`](eval-seed-questions.json) chỉ là con trỏ tới file canonical để tránh hai bản dữ liệu lệch nhau.
- Quy mô: **120 câu**, đúng 10 dev + 10 holdout mỗi nhóm.
- Đã kiểm tra nguồn cho các câu C1/C3/C4 bằng văn bản Luật 36/2024/QH15 có text layer; C2 dựa trên Nghị định 168/2024/NĐ-CP và 238/2026/NĐ-CP đang QUARANTINED.
- Các câu C2/C5/C6 là kiểm tra hành vi, không cần Điều/Khoản đích.
- **Chưa có chữ ký người kiểm tra pháp luật**; `reviewer` để `null` và `verificationStatus` là `source-checked-ai` hoặc `design-verified`. Không được gọi là “đã kiểm chứng pháp lý” cho đến khi OD-01 hoàn tất.

### 7.2. Mở rộng lên 120 câu

- Người phụ trách: C + người kiểm tra pháp luật.
- Hạn: 20/11/2026 (OD-10).
- Quy tắc mở rộng: giữ đúng 10 dev + 10 holdout mỗi nhóm; không chuyển câu holdout sang dev; không thêm câu ngoài phạm vi MVP.
- Mỗi câu mới phải có nguồn/Điều/Khoản hoặc lý do thiếu nguồn, ngày áp dụng và điều kiện chấp nhận.
- Trạng thái Phase 8: đã mở rộng kỹ thuật đủ 120 câu và chạy harness; **ký duyệt pháp lý toàn bộ holdout vẫn còn mở**.
- Trước khi dùng chính thức, người kiểm tra pháp luật ký duyệt toàn bộ holdout.

## 8. Tiêu chí nghiệm thu G8

Chỉ coi là đạt G8 khi:

- Đủ 120 câu, đúng cấu trúc 60/60 và 10/10 mỗi nhóm.
- Các mục tiêu ở mục 5 đạt hoặc có giải thích sai lệch được người chấm chấp nhận.
- Không còn lỗi nghiêm trọng: search/fetch trước consent, citation sai nguồn, dùng văn bản sai phiên bản, trừ quota lặp, đọc chéo phiên.
- Có báo cáo tái lập được với đầy đủ phiên bản.
- Có danh sách lỗi còn mở và tác động đến MVP.

## 9. Mẫu ghi kết quả

Mỗi lần chạy lưu tối thiểu:

```json
{
  "runId": "2026-11-20T09-00-00+07:00",
  "datasetVersion": "road-traffic-120-v1",
  "split": "dev",
  "commit": "<git-sha>",
  "promptVersion": "grader-v1",
  "modelId": "gemini-2.5-flash",
  "embeddingSpaceId": "gemini-embedding-001@768:v1",
  "corpusVersion": "road-traffic-2026-11-20",
  "retrievalConfigVersion": "rrf-v1",
  "results": [
    {
      "questionId": "C1-DEV-01",
      "expectedDecision": "ANSWER_LOCAL",
      "actualDecision": "ANSWER_LOCAL",
      "citationIds": ["..."],
      "pass": true,
      "latencyMs": 1234,
      "notes": ""
    }
  ]
}
```

## 10. Vấn đề còn mở

- OD-01: người kiểm tra pháp luật.
- OD-04: OCR PDF scan (Nghị định 168/2024, 238/2026) hay loại khỏi corpus tự động.
- OD-10: đã mở rộng kỹ thuật đủ 120 câu trong Phase 8; ký duyệt pháp lý holdout vẫn còn mở.
- Chưa có số đo hiệu chỉnh threshold 0,75 bằng embedding thật; Phase 8 chỉ đo bằng deterministic double. Phải chạy lại trên Gemini thật trước khi tuyên bố chất lượng live.
