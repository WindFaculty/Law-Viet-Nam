-- User accounts and community legal forum tables.

CREATE TABLE app_users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username varchar(64) NOT NULL,
    display_name varchar(100) NOT NULL,
    password_hash varchar(255) NOT NULL,
    role varchar(32) NOT NULL DEFAULT 'USER',
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_users_username UNIQUE (username),
    CONSTRAINT ck_app_users_username_lower CHECK (username = lower(btrim(username)) AND btrim(username) <> ''),
    CONSTRAINT ck_app_users_display_name CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_app_users_role CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE forum_posts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id uuid NOT NULL,
    title varchar(200) NOT NULL,
    category varchar(64) NOT NULL DEFAULT 'THAO_LUAN',
    content text NOT NULL,
    pinned boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_forum_posts_author
        FOREIGN KEY (author_id) REFERENCES app_users (id) ON DELETE CASCADE,
    CONSTRAINT ck_forum_posts_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_forum_posts_content CHECK (btrim(content) <> ''),
    CONSTRAINT ck_forum_posts_category CHECK (
        category IN ('THAO_LUAN', 'GIAO_THONG', 'DAN_SU', 'LAO_DONG', 'HANH_CHINH', 'KHAC')
    )
);

CREATE INDEX idx_forum_posts_created_at ON forum_posts (pinned DESC, created_at DESC);
CREATE INDEX idx_forum_posts_category ON forum_posts (category, pinned DESC, created_at DESC);
CREATE INDEX idx_forum_posts_author ON forum_posts (author_id);

CREATE TABLE forum_comments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id uuid NOT NULL,
    author_id uuid NOT NULL,
    content text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_forum_comments_post
        FOREIGN KEY (post_id) REFERENCES forum_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_forum_comments_author
        FOREIGN KEY (author_id) REFERENCES app_users (id) ON DELETE CASCADE,
    CONSTRAINT ck_forum_comments_content CHECK (btrim(content) <> '')
);

CREATE INDEX idx_forum_comments_post_created ON forum_comments (post_id, created_at ASC);
CREATE INDEX idx_forum_comments_author ON forum_comments (author_id);

-- Starter community posts owned by a non-login system author (enabled = false).
INSERT INTO app_users (id, username, display_name, password_hash, role, enabled)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'ban_quan_tri',
    'Ban biên tập Pháp Luật Việt Nam',
    '!DISABLED!',
    'ADMIN',
    false
);

INSERT INTO forum_posts (id, author_id, title, category, content, pinned)
VALUES
(
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    'Chào mừng đến với Diễn đàn Pháp Luật Việt Nam',
    'THAO_LUAN',
    'Đây là không gian trao đổi, chia sẻ kinh nghiệm thực tiễn và thảo luận các quy định pháp luật Việt Nam cùng cộng đồng.

Khi đặt câu hỏi hoặc chia sẻ tình huống pháp lý, bạn nên ghi rõ bối cảnh, mốc thời gian và tên văn bản liên quan (nếu có) để mọi người dễ tra cứu và phản hồi chính xác. Với nhu cầu tra cứu điều khoản nguyên văn nhanh, bạn cũng có thể sử dụng mục Hỏi đáp ở thanh điều hướng.',
    true
),
(
    '10000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001',
    'Trao đổi về quy định đội mũ bảo hiểm và mức phạt vượt đèn đỏ mới nhất',
    'GIAO_THONG',
    'Các quy định về trật tự, an toàn giao thông đường bộ thường xuyên được cập nhật qua các nghị định xử phạt vi phạm hành chính. Mọi người cùng thảo luận các tình huống thực tế như:
1. Quy định đội mũ bảo hiểm đối với người điều khiển và người ngồi trên xe mô tô, xe gắn máy, xe đạp máy.
2. Cách xác định lỗi không chấp hành hiệu lệnh của đèn tín hiệu giao thông và mức xử phạt tương ứng.

Hãy để lại bình luận hoặc dẫn chiếu điều khoản bạn quan tâm bên dưới.',
    false
);

INSERT INTO forum_comments (id, post_id, author_id, content)
VALUES
(
    '20000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    'Bạn có thể đăng ký tài khoản tại mục Đăng nhập để tạo chủ đề mới và tham gia bình luận.'
);

