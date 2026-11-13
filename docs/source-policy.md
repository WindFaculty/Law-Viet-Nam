# Chính sách nguồn, chống SSRF và kiểm tra hiệu lực

**Ngày ban hành:** 12/11/2026  
**Trạng thái:** Áp dụng cho MVP; danh sách nguồn và trạng thái parser phải được cập nhật khi có bằng chứng mới.  
**Liên quan:** [quyết định MVP](decisions.md), [báo cáo khả thi Phase 0](reports/phase-0-feasibility.md), [giao thức đánh giá](evaluation-protocol.md).

Tài liệu này quy định nguồn nào được tải, cách chặn SSRF, điều kiện chuyển dữ liệu từ `QUARANTINED` sang `READY`, và cách xác minh ngày hiệu lực. Mục tiêu là không để nội dung Web tùy ý trở thành “căn cứ pháp luật” chỉ vì tải được.

## 1. Nguyên tắc bắt buộc

1. Không tìm kiếm, tải URL hoặc tạo job crawl trước khi có xác nhận hợp lệ của người dùng.
2. Chỉ tải host trong allowlist; không có wildcard, không suy ra host từ kết quả AI.
3. Kết quả tìm kiếm chỉ dùng để phát hiện URL; snippet/title không phải căn cứ pháp luật.
4. Mọi dữ liệu tải về vào `QUARANTINED` trước; chỉ dữ liệu đạt kiểm tra mới được `READY`.
5. Không vượt CAPTCHA, đăng nhập, paywall, robots.txt hoặc cơ chế chặn của nguồn.
6. Không tự mở rộng sang nguồn ngoài allowlist, không tự động crawl thêm khi thiếu căn cứ.
7. Không để AI điền metadata còn thiếu, không suy đoán ngày hiệu lực hoặc quan hệ sửa đổi.
8. Nội dung nguồn là dữ liệu không tin cậy: không thay đổi prompt, quyền gọi công cụ, quota hoặc quy tắc hệ thống.

## 2. Allowlist

### 2.1. Danh sách host được phép

| Host | Mục đích | Loại dữ liệu được tải | Trạng thái parser tại Phase 0 |
| --- | --- | --- | --- |
| `vanban.chinhphu.vn` | Cổng văn bản Chính phủ: tìm kiếm, trang chi tiết, metadata | HTML, PDF | Metadata: đã đọc được. Toàn văn HTML: không có; PDF phụ thuộc file. |
| `datafiles.chinhphu.vn` | CDN file chính thức của cổng văn bản | PDF, file đính kèm | PDF có text layer: đã bóc tách được. PDF scan: từ chối. |
| `congbao.chinhphu.vn` | Công báo điện tử: trang văn bản, metadata | HTML, PDF | Metadata/URL: đã đọc được. Bóc tách toàn văn: chưa chứng minh. |
| `congbaocdn.chinhphu.vn` | CDN PDF của Công báo | PDF | Chưa chứng minh; chỉ dùng khi URL xuất phát từ trang `congbao.chinhphu.vn`. |
| `vbpl.vn` | Cơ sở dữ liệu quốc gia về pháp luật | HTML/metadata | Chỉ metadata. Toàn văn tự động: chưa đạt (JS/reCAPTCHA). |
| `www.vbpl.vn` | Alias chuyển hướng của `vbpl.vn` | HTML/metadata | Chỉ dùng để canonicalize về `vbpl.vn`; không tải nội dung. |

**Quy tắc host:**

- So khớp **chính xác** hostname sau khi lowercase và bỏ dấu chấm cuối; không dùng `endsWith`, regex hoặc wildcard.
- Không cho `*.chinhphu.vn` hoặc bất kỳ subdomain tự do nào. Thêm host mới phải qua quy trình mục 10.
- `datafiles.chinhphu.vn` và `congbaocdn.chinhphu.vn` chỉ được tải nếu URL được phát hiện từ trang chi tiết/tìm kiếm của host cha tương ứng và vượt qua kiểm tra SSRF.
- Không tải `chinhphu.vn`, `baochinhphu.vn`, `phapdien.moj.gov.vn`, blog, thư viện luật tư nhân hoặc trang tổng hợp trong MVP.
- IP literal, `localhost`, hostname nội bộ, URL rút gọn và host có userinfo bị chặn tuyệt đối.

### 2.2. Scheme, port và định dạng

- Chỉ chấp nhận `https://`.
- Chỉ port 443 (không ghi port hoặc ghi đúng 443). Từ chối 80, 8080, 8443 và mọi port khác.
- Từ chối `http`, `file`, `ftp`, `gopher`, `data`, `javascript`, `mailto`, `ws`, `wss`.
- Từ chối URL có username/password (`https://user:pass@host/...`).
- Bỏ fragment khi fetch; giữ query cần thiết cho tài liệu.
- Không coi URL do model trả về là URL hợp lệ; chỉ dùng URL do provider hoặc quản trị viên nhập, sau đó kiểm tra allowlist.

### 2.3. Robots.txt (kiểm tra ngày 12/11/2026)

| Host | Kết quả robots.txt | Quy tắc áp dụng |
| --- | --- | --- |
| `vanban.chinhphu.vn` | 200; `User-agent: *` → `Allow: /` | Được tải trang công khai; vẫn giới hạn tần suất và kích thước. |
| `congbao.chinhphu.vn` | 200; `User-agent: *` → `Allow: /` | Được tải trang công khai; vẫn giới hạn tần suất và kích thước. |
| `vbpl.vn` | 200; `Allow: /` nhưng `Disallow: /api/` và `/Pages/` | **Không** gọi `/api/` hoặc `/Pages/`; chỉ đọc trang công khai dạng `/van-ban/...` và trang chủ. |
| `datafiles.chinhphu.vn` | **403 Forbidden** | Không crawl thư mục, không liệt kê file; chỉ tải URL file cụ thể xuất phát từ trang `vanban.chinhphu.vn` đã được phép. |
| `congbaocdn.chinhphu.vn` | Chưa kiểm tra | Chỉ tải URL file cụ thể xuất phát từ trang `congbao.chinhphu.vn`. |

Nếu `robots.txt` không đọc được hoặc trả lỗi, **không tự động crawl**; chỉ tải URL cụ thể đã được con người/allowlist xác nhận. Không dùng `robots.txt` trả 403 để suy ra quyền crawl toàn bộ host.

## 3. Chống SSRF

### 3.1. Kiểm tra trước khi kết nối

1. Parse URL bằng thư viện chuẩn; từ chối URL không parse được hoặc có ký tự điều khiển.
2. Lowercase hostname; bỏ dấu chấm cuối; từ chối host rỗng, host có dấu cách, host dạng số thập phân/hex/octal hoặc IPv6 literal.
3. So khớp hostname với allowlist chính xác.
4. Resolve DNS (A và AAAA). Nếu không resolve được, fail closed.
5. Kiểm tra **mọi** địa chỉ trả về; nếu bất kỳ địa chỉ nào thuộc dải không công khai thì chặn cả request. Các dải phải chặn gồm:
   - IPv4: `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10`, `127.0.0.0/8`, `169.254.0.0/16`, `172.16.0.0/12`, `192.0.0.0/24`, `192.0.2.0/24`, `192.168.0.0/16`, `198.18.0.0/15`, `198.51.100.0/24`, `203.0.113.0/24`, `224.0.0.0/4`, `240.0.0.0/4`.
   - IPv6: `::/128`, `::1/128`, `fc00::/7`, `fe80::/10`, `ff00::/8`, IPv4-mapped private ranges (`::ffff:10.0.0.0/104` tương đương), và mọi địa chỉ không global unicast.
   - Metadata endpoint: `169.254.169.254`, `metadata.google.internal`, các host metadata tương tự.
6. Kết nối tới địa chỉ IP đã kiểm tra, không để thư viện tự resolve lại trong lúc kết nối. Nếu không pin được IP, phải kiểm tra lại ngay sau khi kết nối và hủy nếu địa chỉ thực tế khác.
7. Tắt proxy môi trường (`HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`) hoặc chỉ cho proxy đi qua allowlist tương đương; không để proxy biến URL nội bộ thành URL công khai.

### 3.2. Redirect

- Tắt tự động follow redirect; theo dõi thủ công tối đa **3** lần.
- Mỗi redirect phải được kiểm tra lại từ đầu: scheme, host allowlist, port, DNS/IP, kích thước.
- Không cho redirect từ HTTPS xuống HTTP.
- Không cho redirect sang host ngoài allowlist, kể cả khi host đích là CDN của nguồn.
- Ghi lại chuỗi redirect và lý do chặn.

### 3.3. Giới hạn tải

| Tham số | Giá trị |
| --- | --- |
| Connect timeout | 5 giây |
| Read timeout / URL | 30 giây |
| Tổng thời gian tải một URL | 45 giây (bao gồm redirect) |
| Kích thước tối đa | 15 MB (kiểm tra `Content-Length` và đếm byte khi stream) |
| Redirect tối đa | 3 |
| Số URL / job | 5 |
| Content-Type được nhận | `text/html`, `application/xhtml+xml`, `application/pdf`, `text/plain` |
| Encoding | UTF-8; nếu không khai báo, thử UTF-8 rồi Windows-1258/ISO-8859-1 có kiểm soát |
| Nén | Chấp nhận gzip/br nhưng giới hạn kích thước sau giải nén; từ chối nếu vượt trần |
| TLS | Xác thực chứng chỉ và hostname; không tắt kiểm tra, không dùng trust-all |

- Từ chối `application/octet-stream` trừ khi sniff thấy chữ ký PDF và URL kết thúc bằng `.pdf` hoặc đến từ CDN PDF đã allowlist.
- Không tải file nén, macro, executable, HTML có script chạy tự động.
- PDF chỉ được đọc text layer; không thực thi JavaScript nhúng, không mở file đính kèm, không chạy macro.
- User-Agent mô tả dự án và kênh liên hệ; không giả trình duyệt để vượt chặn.
- Rate limit: tối đa 1 request/2 giây/host; tôn trọng `robots.txt`; không tải song song quá 2 URL cùng host.
- Không gửi cookie, header xác thực hoặc referrer chứa dữ liệu phiên.

## 4. Luồng tải và cách ly dữ liệu

```text
URL ứng viên
  → canonicalize
  → allowlist + SSRF check
  → consent còn hạn
  → fetch (HTTPS, giới hạn)
  → snapshot bất biến (raw bytes + SHA-256 + headers + fetchedAt)
  → parse theo source/parser version
  → kiểm tra cấu trúc + metadata + hiệu lực
  → QUARANTINED
  → tự động READY nếu đủ điều kiện, ngược lại chuyển người kiểm tra
```

### 4.1. Snapshot

Mỗi lần tải phải lưu tối thiểu:

- URL yêu cầu, URL cuối cùng sau redirect, thời điểm tải.
- HTTP status, `Content-Type`, `Content-Length`, ETag/Last-Modified nếu có.
- Raw bytes hoặc đường dẫn lưu trữ bất biến.
- SHA-256 của raw bytes.
- Parser name/version, source ID, job ID.
- Kết quả kiểm tra allowlist/SSRF.

Không dùng hash thay cho bản nguồn; hash chỉ là dấu kiểm tra tính toàn vẹn.

### 4.2. Canonical URL

- Lowercase scheme/host; bỏ `www.` nếu host alias được khai báo.
- Bỏ tham số tracking: `utm_*`, `fbclid`, `gclid`, `ref`, `source` nếu không ảnh hưởng nội dung.
- Giữ tham số định danh tài liệu (`docid`, `ItemID`, `pageid`, `id`).
- Chuẩn hóa percent-encoding; không tự thêm/bớt dấu `/` nếu làm đổi tài liệu.
- Nếu URL thay đổi nhưng `document_key` + số hiệu + content hash giống, không tạo bản trùng.

## 5. Điều kiện `QUARANTINED → READY`

Dữ liệu chỉ được `READY` khi **tất cả** điều kiện dưới đây đạt. Nếu bất kỳ điều kiện nào không xác minh được, giữ `QUARANTINED` và chuyển người kiểm tra.

### 5.1. Nguồn và snapshot

- URL thuộc allowlist, đã qua SSRF check và redirect check.
- Có snapshot bất biến + SHA-256 + thời điểm tải.
- `Content-Type` và parser phù hợp.
- Có source ID và parser version.
- Không phải trang lỗi, trang đăng nhập, trang CAPTCHA, trang quảng cáo hoặc trang tìm kiếm.

### 5.2. Nhận dạng văn bản

- Có số hiệu/ký hiệu văn bản và tên văn bản.
- Cơ quan ban hành, ngày ban hành, ngày công bố (nếu có), ngày hiệu lực (hoặc đánh dấu chưa xác minh).
- Số hiệu/tên trong file PDF khớp với trang chi tiết/metadata nguồn.
- Không có dấu hiệu văn bản bị cắt, thiếu trang, thiếu phụ lục hoặc “xem tiếp” mà chưa tải phần tiếp theo.

### 5.3. Cấu trúc và nguyên văn

- Bóc tách được ít nhất một Điều; ưu tiên đủ Chương/Điều/Khoản/Điểm theo cấu trúc nguồn.
- Mỗi Điều/Khoản có locator ổn định (số Điều, số Khoản, tên Chương nếu có).
- Tổng số Điều/Chương khớp với mục lục hoặc cấu trúc dự kiến; sai lệch lớn → cách ly.
- Không mất dấu tiếng Việt, số hiệu, ngày tháng, số tiền, đơn vị, ký tự đặc biệt.
- Phần hiển thị dưới nhãn “trích nguyên văn” phải khớp snapshot sau chuẩn hóa khoảng trắng; không do AI viết lại.
- PDF phải có text layer. PDF scan (không có text layer) bị từ chối với mã `UNSUPPORTED_SCANNED_PDF`; MVP không OCR.
- HTML phải có nội dung chính đọc được; nếu chỉ có metadata thì chỉ lưu metadata, không tạo `legal_articles`.

### 5.4. Hiệu lực và quan hệ

- Ngày hiệu lực lấy từ văn bản hoặc metadata chính thức; nếu không có, ghi `UNKNOWN` và cách ly.
- Phân biệt `issued_at`, `published_at`, `effective_from`, `effective_to`; không dùng lẫn.
- Nếu văn bản sửa đổi/bãi bỏ một phần, phải có quan hệ và phạm vi điều khoản bị tác động; nếu không xác minh được → cách ly.
- Nếu ngày hiệu lực ở cấp điều khoản khác ngày hiệu lực toàn văn bản, lưu riêng ở cấp điều khoản.
- Nếu metadata nguồn và nội dung văn bản xung đột → cách ly, không tự chọn một bên.

### 5.5. Trùng lặp và phiên bản

- Khóa `document_key` nhóm các phiên bản của cùng văn bản.
- Mỗi phiên bản có `document_version` hoặc ngày ban hành + số hiệu.
- Unique theo `(document_key, version, content_hash)`; import lặp không nhân đôi.
- URL khác nhưng nội dung/hash giống: ghi alias, không tạo phiên bản mới.
- Nội dung đổi nhưng số hiệu/phiên bản không đổi: tạo snapshot mới, đánh dấu cần kiểm tra, không ghi đè snapshot cũ.

### 5.6. Quyền và độ mới

- `lastVerifiedAt` được cập nhật khi người kiểm tra hoặc job xác minh thành công.
- Quá 30 ngày cho câu trả lời hiện hành → `STALE`; không khẳng định “đang có hiệu lực”.
- Dữ liệu `STALE` vẫn có thể dùng cho câu hỏi lịch sử nếu ngày áp dụng nằm trong khoảng hiệu lực đã xác minh, nhưng phải hiển thị ngày kiểm tra cuối.

### 5.7. Tự động hay thủ công

- **Tự động `READY`** chỉ khi source + parser nằm trong ma trận đã chứng nhận, tất cả kiểm tra ở trên đạt, không có xung đột metadata.
- **Thủ công** khi: PDF scan, metadata thiếu/mâu thuẫn, cấu trúc lạ, số Điều bất thường, quan hệ sửa đổi chưa rõ, nguồn mới chưa chứng nhận.
- Người duyệt phải ghi tên, thời gian, lý do, phạm vi duyệt. Không có “duyệt toàn bộ nguồn” một lần cho mọi văn bản.
- Người dùng đồng ý tìm Web **không** đồng nghĩa duyệt xuất bản dữ liệu.

## 6. Quy tắc xác minh ngày hiệu lực

### 6.1. Thứ tự nguồn

1. Điều/khoản về hiệu lực trong chính văn bản (ví dụ “Luật này có hiệu lực thi hành từ ngày…”).
2. Metadata trên trang chi tiết chính thức của `vanban.chinhphu.vn` hoặc `vbpl.vn`.
3. Ngày ban hành/công bố trên Công báo.
4. **Không dùng**: tên file, ngày sửa file, `Last-Modified`, ngày tải, ngày crawl, metadata PDF, hoặc suy luận của AI.

Nếu nguồn 1 và 2 xung đột, cách ly và chuyển người kiểm tra. Nếu chỉ có nguồn 3, chỉ ghi `published_at`; không suy ra `effective_from`.

### 6.2. Định dạng và múi giờ

- Ngày pháp lý là ngày lịch, so sánh theo `Asia/Ho_Chi_Minh`, không dùng UTC midnight.
- Chấp nhận `dd/MM/yyyy`, `dd-MM-yyyy`, `yyyy-MM-dd`; chuẩn hóa về `yyyy-MM-dd`.
- Ngày không đầy đủ (“đầu năm 2025”) không được tự điền; cách ly hoặc hỏi lại.

### 6.3. Hiệu lực cấp điều khoản

- Lưu `effective_from`, `effective_to`, `status` ở cấp Điều/Khoản khi văn bản quy định riêng.
- Ví dụ đã xác minh: Luật 36/2024/QH15 có hiệu lực 01/01/2025, nhưng khoản 3 Điều 10 có hiệu lực 01/01/2026 (Điều 88 khoản 2). Không được dùng ngày 01/01/2025 cho khoản 3 Điều 10.
- Khi chọn context cho câu hỏi, lọc theo ngày áp dụng ở cấp điều khoản, không chỉ theo ngày hiệu lực toàn văn bản.

### 6.4. Văn bản bị thay thế/bãi bỏ

- Lưu quan hệ `AMENDS`, `REPEALS`, `REPLACES`, `CONSOLIDATES` khi xác minh được.
- Với văn bản hết hiệu lực toàn bộ, vẫn giữ để trả lời câu hỏi lịch sử; không xóa.
- Với quy định chuyển tiếp, lưu điều khoản và thời hạn; không tự coi là còn hiệu lực vô thời hạn.
- Không tự hợp nhất văn bản; luật sửa đổi và văn bản gốc là các phiên bản riêng.

### 6.5. Câu hỏi hiện hành và độ mới

- Câu trả lời khẳng định “hiện hành/đang có hiệu lực” chỉ dùng dữ liệu có `lastVerifiedAt` ≤ 30 ngày.
- Quá hạn → `STALE`; hệ thống có thể trả lời phần nguyên văn kèm ngày kiểm tra cuối, nhưng không khẳng định tình trạng hiện tại.
- Nếu cần khẳng định hiện hành mà dữ liệu `STALE`, phải kiểm tra lại nguồn hoặc chờ người dùng xác nhận tìm Web.

## 7. Citation và truy vết

- Citation phải trỏ tới `legal_documents` + `legal_articles` + locator (Điều/Khoản/Điểm) + snapshot hash.
- Không lưu một chuỗi URL do AI cung cấp làm citation duy nhất.
- Nếu nguồn ngoài không có anchor ổn định, mở đúng đoạn ở trang nội bộ và kèm link văn bản nguồn; không tạo anchor giả.
- Khi hiển thị “nguồn”, phải phân biệt: văn bản gốc, trang chi tiết, file PDF, và bản snapshot nội bộ.
- Không hiển thị nội dung đã bị cách ly như dữ liệu đã duyệt.

## 8. Parser và phát hiện thay đổi cấu trúc

- Mỗi cặp `(source, parser_version)` có golden sample: URL, snapshot hash, số Điều/Khoản dự kiến, các trích đoạn kiểm tra.
- Trước khi publish parser mới, chạy golden sample và so sánh; sai lệch → không publish.
- Khi HTML/PDF nguồn đổi cấu trúc, parser phải fail closed: dữ liệu mới vào `QUARANTINED`, parser cũ bị tắt cho nguồn đó.
- Không tự sửa parser bằng AI hoặc tự đoán selector mới trong production.
- Ghi `parser_version` vào snapshot và `legal_documents` để tái lập.

## 9. Hành vi bị cấm

- Tìm kiếm/tải trước xác nhận.
- Tải host ngoài allowlist, kể cả qua redirect, DNS rebinding hoặc URL rút gọn.
- Tải IP nội bộ, metadata endpoint, localhost, file system.
- Vượt CAPTCHA, đăng nhập, paywall, robots.txt.
- Gửi cookie/auth header của người dùng tới nguồn.
- Chạy JavaScript/HTML không tin cậy trong ứng dụng.
- Dùng snippet/title của search làm căn cứ pháp luật.
- Dùng OCR trong MVP để “đọc” PDF scan.
- Để AI điền ngày hiệu lực, số hiệu, cơ quan ban hành hoặc quan hệ sửa đổi.
- Tự động mở rộng crawl khi không đủ căn cứ.

## 10. Thay đổi allowlist

Thêm host mới phải có:

1. Lý do và phạm vi sử dụng.
2. Xác nhận host thuộc cơ quan nhà nước/cổng chính thức.
3. Ít nhất 2 URL mẫu tải thành công, có metadata và cấu trúc Điều/Khoản.
4. Kiểm tra SSRF, redirect, TLS, robots.
5. Golden sample parser và người duyệt.
6. Cập nhật tài liệu này + test tự động của allowlist.

Không thêm wildcard. Không thêm host chỉ vì “tiện tìm thấy trên Google”.

## 11. Sự cố và feature flag

- Khi nguồn đổi cấu trúc, bị chặn hoặc có dấu hiệu giả mạo: tắt parser/nguồn bằng feature flag, giữ dữ liệu cũ, không xóa snapshot.
- Khi phát hiện dữ liệu sai đã `READY`: chuyển `QUARANTINED`, ẩn khỏi retrieval, ghi audit và thông báo người kiểm tra.
- Khi provider tìm kiếm lỗi: trả thông báo tạm thời không khả dụng; không âm thầm đổi sang nguồn ngoài allowlist.
- Không xóa lịch sử hội thoại hoặc citation đã lưu khi tắt crawler.

## 12. Bằng chứng Phase 0

Các kết quả kiểm tra thực tế (HTTP status, kích thước, bóc tách PDF, lỗi scan) nằm trong [báo cáo khả thi Phase 0](reports/phase-0-feasibility.md). Tóm tắt:

- `vanban.chinhphu.vn`, `datafiles.chinhphu.vn`, `congbao.chinhphu.vn`, `vbpl.vn` đều trả HTTP 200 cho trang chủ/trang mẫu tại thời điểm kiểm tra.
- `vanban.chinhphu.vn` tìm kiếm được URL Luật 36/2024/QH15 và Nghị định 168/2024/NĐ-CP.
- PDF Luật 36/2024/QH15 có text layer, bóc tách được 89 Điều; PDF Nghị định 168/2024/NĐ-CP và 238/2026/NĐ-CP là ảnh scan, không có text layer.
- `vbpl.vn` trả metadata nhưng toàn văn không đọc được bằng HTTP/headless trong điều kiện kiểm tra; chưa được dùng làm nguồn text tự động.
