-- ==========================================================
-- โครงสร้างฐานข้อมูลสำหรับระบบบันทึกผู้มาติดต่อ (Visitor Management System)
-- คัดลอกคำสั่ง SQL ทั้งหมดนี้ไปวางที่ Supabase > SQL Editor แล้วกด RUN
-- ==========================================================

-- 1. ตารางบัตรผู้มาติดต่อ (Visitor Passes)
CREATE TABLE IF NOT EXISTS visitor_passes (
    pass_code VARCHAR(50) PRIMARY KEY,       -- รหัสบัตร เช่น 'V-001', 'V-002' (ตรงกับข้อความใน QR Code)
    status VARCHAR(20) DEFAULT 'AVAILABLE',  -- 'AVAILABLE' (ว่าง), 'IN_USE' (กำลังถูกใช้งาน)
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. ตารางบันทึกการเข้า-ออก (Visitor Logs)
CREATE TABLE IF NOT EXISTS visitor_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pass_code VARCHAR(50) REFERENCES visitor_passes(pass_code),
    id_card_number VARCHAR(20),              -- เลขบัตร ปชช. 13 หลัก
    title VARCHAR(50),                       -- คำนำหน้า
    first_name VARCHAR(100),                 -- ชื่อ
    last_name VARCHAR(100),                  -- นามสกุล
    birth_date VARCHAR(50),                  -- วันเกิด
    address TEXT,                            -- ที่อยู่ตามบัตร
    contact_person VARCHAR(150),             -- มาติดต่อใคร / บุคคลที่เข้าพบ
    department_or_house VARCHAR(100),        -- แผนก / จุดที่เข้าพบ / บ้านเลขที่
    license_plate VARCHAR(50),               -- ทะเบียนรถ
    purpose VARCHAR(100),                    -- วัตถุประสงค์ (เช่น ส่งของ, ธุระ, ซ่อมบำรุง)
    photo_base64 TEXT,                       -- รูปถ่ายบัตร (หรือ URL รูปภาพ)
    status VARCHAR(20) DEFAULT 'CHECKED_IN', -- 'CHECKED_IN', 'CHECKED_OUT'
    check_in_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    check_out_at TIMESTAMP WITH TIME ZONE,
    guard_in_notes TEXT,                     -- หมายเหตุตอนเข้า
    -- ข้อมูลลายเซ็นผู้รับการติดต่อ (Host Digital Signature)
    name_en VARCHAR(200),                    -- ชื่อ-นามสกุล ภาษาอังกฤษ (ตัวเสริม)
    signed_at TIMESTAMP WITH TIME ZONE,      -- วัน-เวลาที่เซ็นชื่อรับรอง
    signed_by VARCHAR(150),                  -- ชื่อ-นามสกุล ผู้รับการติดต่อที่เซ็นรับรอง
    signature_url TEXT,                      -- ลิงก์ไฟล์ภาพลายเซ็นใน Google Drive
    signature_status VARCHAR(20) DEFAULT 'PENDING', -- 'PENDING', 'SIGNED'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- คำสั่ง Migration สำหรับฐานข้อมูลที่มีอยู่แล้ว
ALTER TABLE visitor_logs ADD COLUMN IF NOT EXISTS name_en VARCHAR(200);
ALTER TABLE visitor_logs ADD COLUMN IF NOT EXISTS signed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE visitor_logs ADD COLUMN IF NOT EXISTS signed_by VARCHAR(150);
ALTER TABLE visitor_logs ADD COLUMN IF NOT EXISTS signature_url TEXT;
ALTER TABLE visitor_logs ADD COLUMN IF NOT EXISTS signature_status VARCHAR(20) DEFAULT 'PENDING';

-- สร้าง Index เพื่อให้ค้นหาตอนยิง QR Code ออกได้เร็วมาก
CREATE INDEX IF NOT EXISTS idx_visitor_logs_pass_status ON visitor_logs(pass_code, status);
CREATE INDEX IF NOT EXISTS idx_visitor_logs_check_in ON visitor_logs(check_in_at DESC);

-- 3. ตารางแผนก/จุดติดต่อ (Visitor Departments) สำหรับดร็อปดาวน์ในฟอร์ม Check-in
CREATE TABLE IF NOT EXISTS visitor_departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    department VARCHAR(150) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- เพิ่มข้อมูลแผนกเริ่มต้น
INSERT INTO visitor_departments (department) VALUES
('ฝ่ายบริหาร / ผู้บริหาร'),
('ฝ่ายทรัพยากรบุคคล (HR)'),
('ฝ่ายจัดซื้อและพัสดุ'),
('ฝ่ายบัญชีและการเงิน'),
('ฝ่ายขายและการตลาด'),
('ฝ่ายเทคโนโลยีสารสนเทศ (IT)'),
('ฝ่ายคลังสินค้า / สโตร์ / โลจิสติกส์'),
('ฝ่ายผลิตและซ่อมบำรุง'),
('แผนกต้อนรับ / ประชาสัมพันธ์'),
('งานรักษาความปลอดภัย / ก่อสร้าง'),
('บ้านพัก / ห้องชุด / ที่อยู่อาศัย')
ON CONFLICT DO NOTHING;

-- 4. ตารางผู้ดูแลระบบ (Admin Users) สำหรับเข้าสู่ระบบ Dashboard
CREATE TABLE IF NOT EXISTS admin_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- เพิ่มข้อมูล Admin เริ่มต้น (หากยังไม่มี)
INSERT INTO admin_users (username, password, email) VALUES
('admin', '1234', 'admin@vms.local')
ON CONFLICT (username) DO NOTHING;

-- 5. เพิ่มข้อมูลบัตรเริ่มต้น V-001 ถึง V-030 (หากยังไม่มี)
INSERT INTO visitor_passes (pass_code, status)
SELECT 
    'V-' || LPAD(i::text, 3, '0'),
    'AVAILABLE'
FROM generate_series(1, 30) AS i
ON CONFLICT (pass_code) DO NOTHING;

-- 6. ตั้งค่าสิทธิ์ความปลอดภัย (Row Level Security - RLS)
ALTER TABLE visitor_passes ENABLE ROW LEVEL SECURITY;
ALTER TABLE visitor_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE visitor_departments ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_users ENABLE ROW LEVEL SECURITY;

-- อนุญาตให้เว็บเข้าถึงผ่าน Anon Key ได้ (สามารถปรับตามระดับความปลอดภัยที่ต้องการ)
DROP POLICY IF EXISTS "Allow public read-write passes" ON visitor_passes;
CREATE POLICY "Allow public read-write passes" ON visitor_passes FOR ALL USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "Allow public read-write logs" ON visitor_logs;
CREATE POLICY "Allow public read-write logs" ON visitor_logs FOR ALL USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "Allow public read-write departments" ON visitor_departments;
CREATE POLICY "Allow public read-write departments" ON visitor_departments FOR ALL USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "Allow public read-write admin_users" ON admin_users;
CREATE POLICY "Allow public read-write admin_users" ON admin_users FOR ALL USING (true) WITH CHECK (true);
