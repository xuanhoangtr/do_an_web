# UTEWedding — Nền Tảng Thiệp Cưới Trực Tuyến & Sàn Dịch Vụ Cưới

> Chạy trên: **[https://xuanhoang.name.vn/](https://xuanhoang.name.vn/)**

---

## Giới thiệu tổng quan

UTEWedding là hệ thống website hỗ trợ các cặp đôi chuẩn bị cho ngày cưới: tự thiết kế thiệp cưới online gửi cho khách mời, quản lý danh sách khách và phản hồi tham dự RSVP, cùng sàn thương mại điện tử mua sắm phụ kiện cưới, thiệp in ấn từ nhiều cửa hàng trên cùng một nền tảng.

Dự án được xây dựng trên nền tảng Spring Boot 3 (Java 21), giao diện Thymeleaf kết hợp JavaScript và Tailwind CSS, cơ sở dữ liệu PostgreSQL quản lý migration bằng Flyway, hỗ trợ API Gateway qua Kong và triển khai bằng Docker.

---

## Danh sách chức năng

### 1. Nền tảng và bảo mật
- Đăng nhập phiên web và JWT RS256, JWKS cho API
- Mã hóa mật khẩu Argon2id, có lộ trình chuyển từ BCrypt
- Giới hạn tần suất rate limit, CSRF, tiêu đề bảo mật CSP
- Mã hóa trường dữ liệu nhạy cảm
- Chấm điểm rủi ro và xử lý phiên rủi ro
- Thu hồi toàn bộ phiên sau khi đặt lại mật khẩu, thu hồi họ phiên
- Người dùng tự xem và thu hồi từng phiên đăng nhập
- Nhật ký audit và tra cứu ở admin
- Yêu cầu quyền riêng tư, xuất hoặc xóa dữ liệu, xóa danh bạ chat
- Kiểm tra cấu hình production khi khởi động

### 2. Tài khoản người dùng
- Đăng ký kèm OTP kích hoạt qua email
- Đăng nhập, đăng xuất
- Quên mật khẩu, đặt lại mật khẩu kèm thu hồi phiên
- Hồ sơ cá nhân, đổi mật khẩu
- Sổ địa chỉ gắn danh mục hành chính tỉnh, huyện, xã
- Danh sách sản phẩm yêu thích

### 3. Thiệp cưới trực tuyến
- Trình dựng thiệp nhiều bước stepper và xem trước
- Thư viện mẫu thiệp
- Album ảnh qua Cloudinary
- Danh sách khách mời
- Phản hồi tham dự RSVP của khách
- Lời chúc qua kiểm duyệt trước khi công khai, có thông báo lời chúc
- Khôi phục thiệp đã xóa

### 4. Danh mục và sản phẩm
- Danh sách sản phẩm, lọc theo danh mục, chất liệu, khoảng giá, sắp xếp, tìm kiếm
- Chi tiết sản phẩm và biến thể SKU
- Các khối xếp hạng: nổi bật, mới nhất, bán chạy, đánh giá cao, yêu thích
- Đánh giá sản phẩm có kiểm duyệt
- Sản phẩm đã xem gần đây có trang riêng trong tài khoản, tự dọn dẹp định kỳ
- Trang shop công khai và thư mục cửa hàng `/shops`

### 5. Marketplace và phía Vendor
- Đăng ký mở shop và chờ duyệt
- Admin quản lý và duyệt shop, xem lịch sử trạng thái shop
- Hồ sơ shop của chủ shop
- Thành viên shop và phân quyền theo tư cách thành viên
- Không gian làm việc vendor dashboard
- Quản lý sản phẩm của shop
- Quản lý tồn kho, ghi nhận điều chỉnh tồn kho
- Quản lý đơn hàng phía vendor
- Voucher riêng của từng shop
- Địa chỉ gửi hàng của shop, gắn nhà vận chuyển theo shop
- Báo giá vận chuyển theo shop
- Tích hợp tài khoản GHN riêng của từng shop
- Đăng ký chiến dịch quảng cáo nội bộ và admin duyệt bài quảng cáo
- Vendor xem và phản hồi đánh giá của shop mình

### 6. Tài chính Vendor
- Admin cấu hình chính sách hoa hồng
- Lưu snapshot hoa hồng và sổ cái tài chính theo đơn
- Giữ tiền và trạng thái tài chính của shop
- Kỳ quyết toán và điều kiện đủ chuẩn quyết toán
- Chi trả cho shop, tài khoản nhận tiền, thao tác chi trả
- Xử lý nợ thu hồi khi chi trả sai
- Trang tài chính của vendor và dashboard tài chính cho admin
- Biên nhận quỹ nền tảng

### 7. Giỏ hàng và checkout đa shop
- Giỏ hàng nhóm theo shop
- Xem trước checkout theo nhóm shop
- Đặt hàng theo nhóm, mỗi shop thành một đơn con
- Thanh toán theo nhóm checkout
- Phân bổ hoàn tiền theo shop
- Voucher gồm giảm giá và giảm phí vận chuyển của sàn và của shop

### 8. Thanh toán
- Thanh toán khi nhận hàng COD và đối soát thu hộ
- VNPay sandbox: tạo giao dịch, trang trả về, IPN
- MoMo sandbox
- Hoàn tiền, gồm trường hợp ghi nhận muộn đã xác minh

### 9. Vận chuyển và giao hàng
- Quản lý nhà vận chuyển ở admin: tạo, sửa, đặt mặc định, bật tắt, xóa, khôi phục
- Báo giá vận chuyển đọc phí từ cơ sở dữ liệu qua snapshot
- Chế độ báo giá vận chuyển
- Tích hợp GHN staging và webhook nhận sự kiện
- Đối soát sự kiện vận chuyển theo nhà vận chuyển và từng shop
- Vận hành giao hàng ở admin xử lý sự kiện chưa khớp
- Shipper nội bộ: danh sách việc, quy trình fulfillment
- Tra cứu đơn hàng công khai
- Admin cấu hình vận chuyển theo shop

### 10. Đơn hàng và hậu mãi
- Lịch sử và chi tiết đơn hàng của khách
- Hủy đơn có kiểm tra trạng thái fulfillment, chỉ hủy fulfillment đang mở
- Trả hàng, hoàn tiền qua nhiều cấp duyệt
- Admin duyệt sản phẩm và nội dung

### 11. Chat và hỗ trợ khách hàng
- Chat khách với nền tảng: hội thoại, gửi tin, báo đang gõ, đã đọc, đính kèm ảnh, đếm chưa đọc
- Chat khách với từng shop
- Vendor trả lời chat của shop
- Hộp thư chat cho nhân viên vận hành
- Trung tâm tin nhắn của khách gộp các cuộc chat
- Danh bạ chat khách và shop, xóa dữ liệu chat theo yêu cầu riêng tư
- Ticket hỗ trợ truyền thống

### 12. Realtime và thông báo
- WebSocket, STOMP với các kênh đích theo người dùng
- Thông báo trong tài khoản
- Thông báo qua email

### 13. Quản trị hệ thống
- Dashboard quản trị
- Quản lý người dùng và RBAC 7 vai trò: USER, CUSTOMER, VENDOR, SHIPPER, MANAGER, ADMIN, SUPER_ADMIN
- Hàng chờ việc cần chú ý cho vận hành
- Cổng route theo feature flag cho từng phân hệ marketplace

### 14. Vận hành và triển khai
- Docker Compose với PostgreSQL, Kong gateway và ứng dụng
- Migration cơ sở dữ liệu V1–V117
- Script sao lưu và quan trắc
- Bộ test tự động: 256 file test Java, có 30 file test riêng cho vendor và các test cho marketplace, nhà vận chuyển, chi trả, quyết toán

---

### Chức năng chưa có trong bản này
- Gợi ý nội dung bằng AI: chưa có tích hợp Gemini hay dịch vụ AI
- Mã QR cho thiệp cưới và check-in khách mời: chưa có thư viện tạo QR và chưa có chức năng tương ứng trong code

---

## Hướng dẫn cài đặt và chạy thử nghiệm

### 1. Yêu cầu môi trường
- Java 21 trở lên
- Maven 3.9+ (hoặc dùng `./mvnw`)
- Docker và Docker Compose

### 2. Khởi chạy cơ sở dữ liệu và Gateway
```bash
docker compose -f deploy/docker-compose.yml up -d postgres kong
```

### 3. Cấu hình biến môi trường
Tạo file `.env` từ file mẫu `.env.example`:
```bash
cp .env.example .env
```

### 4. Khởi chạy ứng dụng
```bash
./mvnw clean spring-boot:run
```
Truy cập ứng dụng tại `http://localhost:8080` (hoặc qua Kong Gateway tại `http://localhost:8000`).

---

## Nhóm thực hiện

- Trần Xuân Hoàng ([@xuanhoangtr](https://github.com/xuanhoangtr))
- Nguyễn Anh Minh ([@anhminh298](https://github.com/anhminh298))
