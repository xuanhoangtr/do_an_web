# Đồ Án Tốt Nghiệp: Hệ Thống Thiệp Cưới Điện Tử & Sàn Thương Mại Dịch Vụ Cưới UTEWedding

## 1. Giới thiệu dự án
UTEWedding là nền tảng số hỗ trợ toàn diện cho các cặp đôi chuẩn bị lễ cưới, bao gồm:
- Tạo và thiết kế thiệp cưới điện tử trực tuyến, gửi lời mời và quản lý phản hồi khách mời (RSVP).
- Sàn thương mại dịch vụ cưới kết nối các nhà cung cấp (thiệp in, hoa cưới, lễ phục, quà cưới, chụp ảnh).
- Tích hợp cổng thanh toán trực tuyến, dịch vụ giao vận toàn quốc và kênh hỗ trợ trực tuyến realtime.

## 2. Công nghệ sử dụng
- **Backend**: Spring Boot 3.3, Java 21, Spring Security, Spring Data JPA, Flyway Migration, WebSocket STOMP
- **Frontend**: Thymeleaf, Bootstrap 5, Custom CSS, Modern JavaScript
- **Cơ sở dữ liệu**: PostgreSQL 16
- **Hạ tầng & Triển khai**: Docker, Docker Compose, Kong API Gateway

## 3. Thành viên thực hiện
1. **Trần Xuân Hoàng** - Kiến trúc hệ thống, Backend, CSDL, Thanh toán & Vận chuyển
2. **Nguyễn Anh Minh** - Thiết kế UI/UX, Frontend Thymeleaf, Controller & Tương tác khách hàng

## 4. Tiến độ các giai đoạn
- Giai đoạn 1 (15/09 - 15/10): Xây dựng khung kiến trúc, phân quyền, module thiệp cưới, catalog sản phẩm và cổng thanh toán.
- Giai đoạn 2 (16/10 - 27/10): Giai đoạn nước rút - tích hợp giao vận GHN, sàn người bán đa gian hàng, STOMP chat realtime, đối soát COD và hoàn thiện chốt release v1.0.0.
