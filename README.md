# Kotoba — Japanese for IT

Web app học tiếng Nhật chuyên ngành và luyện phỏng vấn tại Nhật. Backend dùng Spring Boot 4.2.0/Java 17, giao diện tĩnh HTML/CSS/JavaScript giao tiếp qua REST API. Hiện tại dự án không dùng Thymeleaf templates hoặc Spring Security web starter; xác thực phiên và CSRF được xử lý trong ứng dụng.

Không gian học yêu cầu đăng nhập và có sổ từ, flashcard/trắc nghiệm toàn màn hình, luyện phỏng vấn theo ngành, hội thoại đón khách/làm quen, cùng cẩm nang kính ngữ. AI dùng Cohere Compatibility API để soạn câu trả lời phỏng vấn và tự nhận diện tiêu đề cột không chuẩn khi nhập PDF/CSV; PDF dạng ảnh scan cần OCR trước khi nhập.

## Chạy local

Yêu cầu Java 17+ và Docker Compose.

```powershell
docker compose up -d mysql
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "local-dev-password"
.\gradlew.bat bootRun
```

`application.properties` mặc định dùng database `itjp_interview` và thông tin local tương ứng trong `docker-compose.yml`. Nếu dùng MySQL cài riêng, đặt `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` theo cấu hình của bạn. Dialect MySQL được chỉ định rõ; nếu MySQL chưa chạy, database chưa tồn tại hoặc thông tin kết nối sai thì cần khởi động/tạo database hoặc sửa các biến này (chỉ đặt dialect không thể thay thế kết nối DB).

Mở http://localhost:8080 để xem trang giới thiệu Kotoba; tạo tài khoản để vào không gian học và lưu từ vựng. Chỉ cấu hình AI khi cần phân tích từ, nhận diện cột hoặc soạn câu trả lời phỏng vấn:

```powershell
$env:AI_API_KEY = "<Cohere API key>"
# Cohere Compatibility API và model đã được cấu hình mặc định.
.\gradlew.bat bootRun
```

Đặt `AI_API_KEY` trong environment/Run Configuration thay vì ghi trực tiếp API key vào file. Nếu key từng được lưu trong file hoặc commit, hãy revoke/rotate key đó trên Cohere và chỉ dùng key mới qua biến môi trường.

## Triển khai VPS (Docker Compose + HTTPS)

Kotoba là ứng dụng Spring Boot có phiên đăng nhập và kết nối MySQL nên phù hợp triển khai trên VPS/container chạy liên tục hơn Vercel Functions. Repo có sẵn `Dockerfile`, `docker-compose.prod.yml` và `Caddyfile`; Caddy tự cấp/gia hạn HTTPS. Trên VPS Linux cần cài Docker Engine và Docker Compose plugin, mở cổng inbound `80/tcp`, `443/tcp` (và `443/udp` cho HTTP/3), đồng thời trỏ bản ghi DNS `A`/`AAAA` của domain về VPS.

```bash
cp .env.example .env
```

Sửa `.env`: đặt `DOMAIN` thành domain thật, thay `DB_PASSWORD` bằng mật khẩu ngẫu nhiên dài (ví dụ `openssl rand -base64 36`) và điền `AI_API_KEY` nếu cần tính năng AI. Không commit hoặc gửi file `.env` lên Git. Sau đó chạy:

```bash
docker compose --env-file .env -f docker-compose.prod.yml up -d --build
docker compose --env-file .env -f docker-compose.prod.yml logs -f app
```

Trang web được phục vụ tại `https://<DOMAIN>`. MySQL không publish ra Internet; dữ liệu lưu trong volume `mysql-data`. Sao lưu volume/database thường xuyên và giữ bản sao `.env` ở nơi an toàn. Cập nhật phiên bản bằng cách kéo source mới rồi chạy lại lệnh `up -d --build`.

Vercel không phải lựa chọn phù hợp cho cấu hình hiện tại: backend cần một tiến trình Java lâu chạy, session và MySQL có trạng thái; triển khai Vercel sẽ cần tách frontend/backend, chuyển session sang dịch vụ ngoài và cấu hình database riêng.

Không ghi khóa API hay mật khẩu thật vào mã nguồn. Cấu hình database qua `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; khi triển khai HTTPS, đặt `COOKIE_SECURE=true`. Cohere được gọi qua `https://api.cohere.ai/compatibility/v1/chat/completions` với model `command-a-plus-05-2026`; có thể đổi bằng `AI_BASE_URL` và `AI_MODEL`. AI và từ điển được gọi từ backend, khóa AI không gửi xuống trình duyệt. Nếu không cấu hình AI, chức năng học từ, nhập file theo định dạng chuẩn, tài khoản, từ điển tiếng Anh từ Jisho và câu hỏi phỏng vấn vẫn hoạt động.

## Triển khai Render

Repo dùng Gradle Wrapper, Java 17, Spring Boot 4.2.0 và MySQL Connector/J. Render Blueprint `render.yaml` triển khai Web Service qua Docker từ `Dockerfile` có sẵn; Dockerfile build executable JAR `build/libs/app.jar` bằng `./gradlew bootJar`, sau đó chạy JAR bằng Java 17. Render tự cấp `PORT` (mặc định 10000), Spring Boot đọc biến này qua `server.port=${PORT:8080}`; khi chạy local, cổng mặc định vẫn là 8080. Tài nguyên giao diện nằm trong `src/main/resources/static` và được Spring Boot đóng gói vào JAR. Không có thư mục template Thymeleaf trong dự án, nên không thêm Thymeleaf vào build.

Không chuyển database sang PostgreSQL: ứng dụng hiện dùng MySQL, driver `com.mysql:mysql-connector-j` và `MySQLDialect`. Blueprint cố ý yêu cầu `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` từ Render, không chứa thông tin đăng nhập. Tạo một MySQL database riêng ở nhà cung cấp ngoài Render (hoặc dùng MySQL server hiện có), bật truy cập mạng từ Render theo hướng dẫn nhà cung cấp, rồi nhập:

- `DB_URL`: JDBC URL đầy đủ, ví dụ `jdbc:mysql://HOST:3306/DATABASE?useSSL=true&serverTimezone=UTC` (dùng host, database name và yêu cầu TLS do nhà cung cấp cấp).
- `DB_USERNAME`, `DB_PASSWORD`: tài khoản MySQL được cấp riêng cho ứng dụng.
- `AI_API_KEY`: tùy chọn; chỉ cần nếu muốn dùng phân tích AI.

Render Free Web Service có thể sleep khi không có lưu lượng và không lưu dữ liệu bền vững trên filesystem cục bộ; không dùng file/local database làm kho dữ liệu. MySQL cần được host riêng, cân nhắc giới hạn kết nối, allowlist/network policy, SSL và chính sách backup của nhà cung cấp. Trong profile `render`, Spring Session lưu session vào hai bảng `SPRING_SESSION` và `SPRING_SESSION_ATTRIBUTES` trên MySQL; script khởi tạo idempotent chỉ tạo bảng nếu chưa có. DB user cần quyền tạo hai bảng session này. Session tồn tại tối đa 12 giờ và vẫn dùng được sau khi service khởi động lại, miễn là dùng cùng database. Cookie là `__Host-KOTOBA_SESSION`, chỉ chứa mã session ngẫu nhiên; được đặt `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/` và không chia sẻ qua domain. Dữ liệu phiên phía server chỉ giữ ID tài khoản và CSRF token; cookie hết khi đóng trình duyệt, còn phiên phía server hết hạn sau tối đa 12 giờ không hoạt động. Các gói miễn phí và điều kiện của Render/nhà cung cấp database có thể thay đổi. Nếu cần tiến trình luôn hoạt động, chọn gói Web Service trả phí thấp nhất đang có trong dashboard Render.

### Deploy từ GitHub

1. Push project lên GitHub và tạo/pick một repository trên Render.
2. Trong Render, chọn **New → Blueprint**, kết nối repository và để Render đọc `render.yaml`. Xác nhận Web Service `kotoba`; Blueprint dùng Docker nên không cần khai báo Maven hay build command riêng trong dashboard.
3. Tạo MySQL database ngoài Render trước khi deploy và điền `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` vào Environment của service. Có thể điền `AI_API_KEY` nếu cần; không commit các giá trị này vào Git hoặc `render.yaml`.
4. Deploy, xem Build/Runtime Logs. Khi service báo Live, mở URL do Render cấp; root path `/` phục vụ trang HTML. CSS/JS được phục vụ từ `/styles.css` và `/app.js`.
5. Để kiểm tra local: chạy `docker compose up -d mysql`, đặt `DB_USERNAME=root` và `DB_PASSWORD=local-dev-password` (hoặc dùng giá trị tương ứng trong compose), rồi chạy `.\gradlew.bat clean test bootJar`. JAR được tạo tại `build/libs/app.jar`; chạy bằng `java -jar build/libs/app.jar`. Docker build tự chạy `./gradlew --no-daemon bootJar`.

### Xử lý sự cố Render

- **Không xác định được dialect / không kết nối database:** xác nhận đủ ba biến DB, JDBC URL có tiền tố `jdbc:mysql://`, host database truy cập được từ Render, database tồn tại, TLS/port/allowlist đúng. Không dùng `localhost` cho database production.
- **Service không qua health check:** xem Runtime Logs, kiểm tra JAR khởi động và database sẵn sàng; `/` là health check hiện tại. App cần kết nối DB trong lúc khởi động.
- **CSS/JS không tải:** kiểm tra URL tài nguyên bắt đầu bằng `/`, trạng thái HTTP trong trình duyệt và Render Logs; deploy lại sau khi push thay đổi.
- **Phiên đăng nhập không giữ được:** đảm bảo HTTPS của Render đang hoạt động, `COOKIE_SECURE=true` và `SERVER_FORWARD_HEADERS_STRATEGY=framework` được giữ nguyên.
- **AI trả lỗi:** kiểm tra `AI_API_KEY`, quota và model; ứng dụng vẫn chạy các chức năng không dùng AI khi không đặt key.

## Tính năng

- Tạo tài khoản/đăng nhập; mật khẩu BCrypt, dữ liệu từ vựng giới hạn theo tài khoản và cookie phiên HttpOnly/SameSite.
- Thêm, xóa, gom từ vào các bộ do người học tự đặt tên, ôn bằng flashcard/trắc nghiệm; từ trùng trong cùng tài khoản được báo lỗi và bản nhập trùng sẽ được bỏ qua. Nhập CSV, TSV, TXT hoặc PDF có lớp văn bản (tối đa 5 MB, 500 mục). CSV hỗ trợ cột Nhật, Hiragana, nghĩa Việt, câu ví dụ; chọn **Dùng AI nhận diện cột** để ánh xạ file có thứ tự/các cột khác.
- Tra cứu trực tiếp Jisho JSON API (`/api/v1/search/words`) và đọc trường `japanese`, `senses`, `parts_of_speech`, `jlpt`, `is_common`, `attribution` từ dữ liệu JMdict. Cohere bổ sung nghĩa tiếng Việt, âm Hán Việt và câu ví dụ đã dịch khi cấu hình key.
- Gọi Cohere Compatibility API để phân tích từ ngắn gọn gồm cách đọc, Hán Việt, âm On/Kun, ví dụ Nhật–Việt và ngữ cảnh thường gặp trong Mondai JLPT; model mặc định `command-a-plus-05-2026`.
- Câu hỏi phỏng vấn tiếng Nhật theo nhóm phát triển, cloud, hạ tầng, helpdesk, quản lý dự án và làm việc tại Nhật.
- Soạn câu trả lời phỏng vấn tiếng Nhật ngắn theo phong cách phỏng vấn Nhật từ kinh nghiệm người học, kèm dịch và cấu trúc nguyên nhân/cách xử lý/câu chuyện; prompt yêu cầu không tự bịa dữ kiện.
- Màn hình flashcard tập trung vào thẻ ôn tập, ẩn khu vực thêm và nhập từ cho đến khi quay lại sổ từ.
- Trang giới thiệu Kotoba nhiều phần trước khu vực học, cùng thông báo thao tác hiển thị nhanh ở góc trên bên phải.
- Menu homepage lọc câu hỏi theo ngành nghề; dropdown, cuộn trang và chuyển màn hình có animation nhẹ, tôn trọng cài đặt giảm chuyển động.

## API chính

`/api/session`, `/api/auth/register`, `/api/auth/login`, `/api/auth/logout`, `/api/dashboard`, `/api/vocabulary`, `/api/vocabulary/books`, `/api/vocabulary/import`, `/api/community/books`, `/api/ai/analyze`, `/api/interview/answer`, `/api/dictionary`, `/api/questions`.

Các request thay đổi dữ liệu dùng header `X-CSRF-TOKEN` nhận từ `/api/session` hoặc từ phản hồi đăng nhập/đăng ký.

## Kiểm tra

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
```

CI chạy kiểm thử và đóng gói với MySQL 8.4 trên GitHub Actions.

Khi push lên `main` hoặc `master`, workflow còn build và publish image lên GitHub Container Registry (`ghcr.io/<owner>/<repo>`). Khi chạy image, truyền `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` và tùy chọn `AI_API_KEY`; cấu hình HTTPS bằng `COOKIE_SECURE=true`.
