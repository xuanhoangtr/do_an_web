-- Source dataset: https://github.com/ThangLeQuoc/vietnamese-provinces-database
-- Release: v5.2.0. Core administrative data only; no GIS.

INSERT INTO provinces(code,name,name_en,full_name,full_name_en,code_name,administrative_unit_id,postal_code_prefix) VALUES
('01','Hà Nội','Hanoi','Thành phố Hà Nội','Hanoi City','ha_noi',1,'10, 11, 12, 13, 14'),
('04','Cao Bằng','Cao Bang','Tỉnh Cao Bằng','Cao Bang Province','cao_bang',2,'21'),
('08','Tuyên Quang','Tuyen Quang','Tỉnh Tuyên Quang','Tuyen Quang Province','tuyen_quang',2,'20, 22'),
('11','Điện Biên','Dien Bien','Tỉnh Điện Biên','Dien Bien Province','dien_bien',2,'32'),
('12','Lai Châu','Lai Chau','Tỉnh Lai Châu','Lai Chau Province','lai_chau',2,'30'),
('14','Sơn La','Son La','Tỉnh Sơn La','Son La Province','son_la',2,'34'),
('15','Lào Cai','Lao Cai','Tỉnh Lào Cai','Lao Cai Province','lao_cai',2,'31, 33'),
('19','Thái Nguyên','Thai Nguyen','Tỉnh Thái Nguyên','Thai Nguyen Province','thai_nguyen',2,'23, 24'),
('20','Lạng Sơn','Lang Son','Tỉnh Lạng Sơn','Lang Son Province','lang_son',2,'25'),
('22','Quảng Ninh','Quang Ninh','Thành phố Quảng Ninh','Quang Ninh City','quang_ninh',1,'01, 02'),
('24','Bắc Ninh','Bac Ninh','Thành phố Bắc Ninh','Bac Ninh City','bac_ninh',1,'16, 26'),
('25','Phú Thọ','Phu Tho','Tỉnh Phú Thọ','Phu Tho Province','phu_tho',2,'15, 35, 36'),
('31','Hải Phòng','Haiphong','Thành phố Hải Phòng','Haiphong City','hai_phong',1,'03, 04, 05'),
('33','Hưng Yên','Hung Yen','Tỉnh Hưng Yên','Hung Yen Province','hung_yen',2,'06, 17'),
('37','Ninh Bình','Ninh Binh','Tỉnh Ninh Bình','Ninh Binh Province','ninh_binh',2,'07, 08, 18'),
('38','Thanh Hoá','Thanh Hoa','Tỉnh Thanh Hoá','Thanh Hoa Province','thanh_hoa',2,'40, 41, 42'),
('40','Nghệ An','Nghe An','Tỉnh Nghệ An','Nghe An Province','nghe_an',2,'43, 44'),
('42','Hà Tĩnh','Ha Tinh','Tỉnh Hà Tĩnh','Ha Tinh Province','ha_tinh',2,'45, 46'),
('44','Quảng Trị','Quang Tri','Tỉnh Quảng Trị','Quang Tri Province','quang_tri',2,'47, 48'),
('46','Huế','Hue','Thành phố Huế','Hue City','hue',1,'49'),
('48','Đà Nẵng','Da Nang','Thành phố Đà Nẵng','Da Nang City','da_nang',1,'50, 51, 52'),
('51','Quảng Ngãi','Quang Ngai','Tỉnh Quảng Ngãi','Quang Ngai Province','quang_ngai',2,'53, 54, 60'),
('52','Gia Lai','Gia Lai','Tỉnh Gia Lai','Gia Lai Province','gia_lai',2,'55, 61, 62'),
('56','Khánh Hoà','Khanh Hoa','Tỉnh Khánh Hoà','Khanh Hoa Province','khanh_hoa',2,'57, 59'),
('66','Đắk Lắk','Dak Lak','Tỉnh Đắk Lắk','Dak Lak Province','dak_lak',2,'56, 63, 64'),
('68','Lâm Đồng','Lam Dong','Tỉnh Lâm Đồng','Lam Dong Province','lam_dong',2,'65, 66, 77'),
('75','Đồng Nai','Dong Nai','Thành phố Đồng Nai','Dong Nai City','dong_nai',1,'67, 76'),
('79','Hồ Chí Minh','Ho Chi Minh','Thành phố Hồ Chí Minh','Ho Chi Minh City','ho_chi_minh',1,'70, 71, 72, 73, 74, 75, 78'),
('80','Tây Ninh','Tay Ninh','Tỉnh Tây Ninh','Tay Ninh Province','tay_ninh',2,'80, 82, 83'),
('82','Đồng Tháp','Dong Thap','Tỉnh Đồng Tháp','Dong Thap Province','dong_thap',2,'81, 84'),
('86','Vĩnh Long','Vinh Long','Tỉnh Vĩnh Long','Vinh Long Province','vinh_long',2,'85, 86, 87'),
('91','An Giang','An Giang','Tỉnh An Giang','An Giang Province','an_giang',2,'90, 91, 92'),
('92','Cần Thơ','Can Tho','Thành phố Cần Thơ','Can Tho City','can_tho',1,'94, 95, 96'),
('96','Cà Mau','Ca Mau','Tỉnh Cà Mau','Ca Mau Province','ca_mau',2,'97, 98');

INSERT INTO vn_provinces_metadata(dataset_version, latest_decree, generated_at, source, effective_date)
VALUES ('v5.2.0', '388/NQ-UBTVQH16', TIMESTAMP '2026-09-20 13:13:17', 'https://github.com/ThangLeQuoc/vietnamese-provinces-database', DATE '2026-09-20');

