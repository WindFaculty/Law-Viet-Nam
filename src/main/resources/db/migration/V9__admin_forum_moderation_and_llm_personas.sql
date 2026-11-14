-- Phase 9: forum moderation (soft remove + request tracking), threaded comments,
-- official admin replies, accepted answers, and configurable LLM personalities.

-- 1. Forum posts: visibility status (PUBLISHED/REMOVED) + request status + audit fields.
ALTER TABLE forum_posts
    ADD COLUMN status varchar(32) NOT NULL DEFAULT 'PUBLISHED',
    ADD COLUMN request_status varchar(32) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN removed_reason text,
    ADD COLUMN removed_by varchar(64),
    ADD COLUMN removed_at timestamptz,
    ADD COLUMN edited_at timestamptz,
    ADD CONSTRAINT ck_forum_posts_status CHECK (status IN ('PUBLISHED', 'REMOVED')),
    ADD CONSTRAINT ck_forum_posts_request_status CHECK (request_status IN ('OPEN', 'ANSWERED', 'RESOLVED'));

CREATE INDEX idx_forum_posts_status_request
    ON forum_posts (status, request_status, pinned DESC, created_at DESC);

-- 2. Forum comments: threaded replies, official admin badge, accepted answer & edit timestamp.
ALTER TABLE forum_comments
    ADD COLUMN parent_comment_id uuid,
    ADD COLUMN official_admin_reply boolean NOT NULL DEFAULT false,
    ADD COLUMN accepted_answer boolean NOT NULL DEFAULT false,
    ADD COLUMN edited_at timestamptz,
    ADD CONSTRAINT fk_forum_comments_parent
        FOREIGN KEY (parent_comment_id) REFERENCES forum_comments (id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_forum_comments_parent_not_self
        CHECK (parent_comment_id IS NULL OR parent_comment_id <> id);

CREATE INDEX idx_forum_comments_parent
    ON forum_comments (post_id, parent_comment_id, created_at ASC);

CREATE UNIQUE INDEX uq_forum_comments_one_accepted_per_post
    ON forum_comments (post_id)
    WHERE accepted_answer = true;

-- Mark starter admin welcome comment as an official admin reply.
UPDATE forum_comments
SET official_admin_reply = true
WHERE id = '20000000-0000-0000-0000-000000000001';

UPDATE forum_posts
SET request_status = 'ANSWERED'
WHERE id = '10000000-0000-0000-0000-000000000001';

-- 3. LLM personalities (Chuyên nghiệp [default], Ngắn gọn, Đầy đủ + Admin extensible).
CREATE TABLE llm_personas (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(64) NOT NULL,
    name varchar(100) NOT NULL,
    description varchar(255) NOT NULL,
    system_instruction text NOT NULL,
    response_style varchar(32) NOT NULL DEFAULT 'CUSTOM',
    is_default boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_llm_personas_code UNIQUE (code),
    CONSTRAINT ck_llm_personas_code CHECK (code ~ '^[A-Z0-9_]+$'),
    CONSTRAINT ck_llm_personas_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_llm_personas_instruction CHECK (btrim(system_instruction) <> ''),
    CONSTRAINT ck_llm_personas_style CHECK (response_style IN ('PROFESSIONAL', 'CONCISE', 'DETAILED', 'CUSTOM'))
);

CREATE UNIQUE INDEX uq_llm_personas_single_default
    ON llm_personas (is_default)
    WHERE is_default = true;

INSERT INTO llm_personas (code, name, description, system_instruction, response_style, is_default, active, display_order)
VALUES
(
    'CHUYEN_NGHIEP',
    'Chuyên nghiệp',
    'Chuẩn mực pháp lý, khách quan, đi thẳng từ kết luận đến căn cứ điều khoản.',
    'Trình bày bằng văn phong pháp lý chuẩn mực, khách quan, lịch sự. Bắt đầu bằng kết luận trực tiếp về việc được phép hay bị cấm/xử phạt, sau đó nêu chính xác Điều/Khoản/Điểm và số hiệu văn bản áp dụng, kết thúc bằng lưu ý ngắn về điều kiện hoặc thời điểm hiệu lực nếu có.',
    'PROFESSIONAL',
    true,
    true,
    1
),
(
    'NGAN_GON',
    'Ngắn gọn',
    'Cô đọng tối đa 1–3 câu, nêu trực tiếp kết quả và điều khoản căn cứ.',
    'Trả lời thật ngắn gọn, súc tích trong tối đa 1 đến 3 câu hoặc vài gạch đầu dòng ngắn. Đi thẳng vào kết luận Có/Không hoặc mức quy định cụ thể và ghi rõ số hiệu văn bản + Điều/Khoản; bỏ qua các giải thích nền tảng dài dòng.',
    'CONCISE',
    false,
    true,
    2
),
(
    'DAY_DU',
    'Đầy đủ',
    'Phân tích chi tiết toàn diện: đối tượng, điều kiện, ngoại lệ và các căn cứ liên quan.',
    'Trình bày đầy đủ, có hệ thống và chi tiết nhất có thể dựa trên ngữ cảnh RAG: (1) Kết luận tổng quát, (2) Phân tích đối tượng áp dụng, hành vi, điều kiện và các trường hợp ngoại lệ nếu có trong văn bản, (3) Đối chiếu đầy đủ từng Điều/Khoản/Điểm và thời điểm hiệu lực liên quan.',
    'DETAILED',
    false,
    true,
    3
);

-- 4. Track selected LLM personality on chat sessions, messages, and retrieval traces.
ALTER TABLE chat_sessions
    ADD COLUMN persona_code varchar(64);

ALTER TABLE chat_messages
    ADD COLUMN persona_code varchar(64);

ALTER TABLE retrieval_traces
    ADD COLUMN persona_code varchar(64);

