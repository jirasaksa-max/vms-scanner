/**
 * =========================================================================
 * การตั้งค่าระบบ Visitor Management System (VMS) สำหรับ GitHub Pages
 * =========================================================================
 */
const VMS_CONFIG = {
  // Supabase Project URL & Anon Key
  SUPABASE_URL: 'https://xshzgwlgwrsoyvmbdbuk.supabase.co',
  SUPABASE_KEY: 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhzaHpnd2xnd3Jzb3l2bWJkYnVrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTAyMzI2MzksImV4cCI6MjEwNTgwODYzOX0.ijZspSAJJMegeIkVIs4HinUauGGJ7Jw7TOrFfo1hXF4',

  // Google Gemini API Key สำหรับระบบ AI อ่านบัตรประชาชนโดยตรง (เร็วมาก ไม่ติดบล็อก)
  // สามารถระบุที่นี่ หรือกรอกผ่านหน้าจอ Admin / ตอนสแกนบัตรครั้งแรก (บันทึกลงเครื่องอัตโนมัติ)
  GEMINI_API_KEY: '',

  // Google Apps Script Web App URL (สำหรับจัดเก็บไฟล์รูปลายเซ็นลง Google Drive)
  GAS_API_URL: 'https://script.google.com/macros/s/AKfycbz3Y6VoFUAZAtKECU4EsM2cwB0LSR2Znio_OTNnWege5TXxJSnIx_WkhuJSpv9QIQ8w/exec'
};

/**
 * ฟังก์ชันกลางสำหรับเรียก Supabase REST API โดยตรง (เร็วและไม่ติดบล็อก CORS)
 */
async function callSupabaseRest(endpoint, method = 'GET', body = null, extraHeaders = {}) {
  const url = `${VMS_CONFIG.SUPABASE_URL}/rest/v1/${endpoint}`;
  const headers = Object.assign({
    'apikey': VMS_CONFIG.SUPABASE_KEY,
    'Authorization': `Bearer ${VMS_CONFIG.SUPABASE_KEY}`,
    'Content-Type': 'application/json',
    'Prefer': 'return=representation'
  }, extraHeaders);

  const options = {
    method: method,
    headers: headers
  };

  if (body && (method === 'POST' || method === 'PATCH' || method === 'PUT')) {
    options.body = JSON.stringify(body);
  }

  const response = await fetch(url, options);
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Supabase Error (${response.status}): ${errorText}`);
  }

  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

/**
 * ฟังก์ชันกลางสำหรับเรียก Google Apps Script Web App (POST API)
 */
async function callGasApi(action, payload = {}) {
  const url = VMS_CONFIG.GAS_API_URL;
  if (!url) throw new Error('ยังไม่ได้กำหนด GAS_API_URL');

  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain;charset=utf-8' },
    body: JSON.stringify(Object.assign({ action: action }, payload))
  });

  return await response.json();
}

/**
 * สกัดข้อมูลบัตรประชาชนด้วย Google Gemini API โดยตรง (Client-Side Direct OCR)
 * วิ่งตรงไปยัง generativelanguage.googleapis.com ไม่ผ่านตัวกลาง ไม่ติดบล็อก 401
 */
async function directGeminiOcr(base64Data, mimeType = 'image/jpeg') {
  let geminiKey = (VMS_CONFIG.GEMINI_API_KEY || '').trim();
  if (!geminiKey) {
    geminiKey = (localStorage.getItem('VMS_GEMINI_KEY') || '').trim();
  }

  // หากยังไม่มี Key ในระบบ ให้แสดง Modal สอบถามผู้ใช้เพียงครั้งเดียว
  if (!geminiKey) {
    if (typeof Swal !== 'undefined') {
      const { value: inputKey } = await Swal.fire({
        title: '🔑 ตั้งค่า Gemini AI API Key',
        html: `
          <div class="text-left text-xs text-slate-600 space-y-2 mb-3">
            <p>ระบบ AI สกัดข้อมูลบัตรประชาชน ทำงานผ่าน <b>Google Gemini 3.1 Flash-Lite</b> โดยตรงเพื่อความรวดเร็วสูงสุด</p>
            <p class="text-slate-400">กรุณาระบุ API Key ของคุณ (ระบบจะบันทึกจำไว้ในเครื่องนี้อัตโนมัติ)</p>
          </div>
        `,
        input: 'text',
        inputPlaceholder: 'AIzaSy...',
        showCancelButton: true,
        confirmButtonText: 'บันทึกและสแกนบัตร',
        cancelButtonText: 'ยกเลิก',
        footer: '<a href="https://aistudio.google.com/app/apikey" target="_blank" class="text-xs text-blue-600 underline">ขอคีย์ฟรีได้ที่ Google AI Studio</a>'
      });

      if (!inputKey || !inputKey.trim()) {
        throw new Error('กรุณาระบุ Google Gemini API Key เพื่อใช้งานระบบอ่านบัตร');
      }

      geminiKey = inputKey.trim();
      localStorage.setItem('VMS_GEMINI_KEY', geminiKey);
    } else {
      geminiKey = prompt('กรุณาระบุ Google Gemini API Key (AIzaSy...):');
      if (!geminiKey) throw new Error('ยังไม่ได้กำหนด Gemini API Key');
      localStorage.setItem('VMS_GEMINI_KEY', geminiKey.trim());
    }
  }

  let cleanBase64 = base64Data;
  if (cleanBase64.indexOf(',') > -1) {
    cleanBase64 = cleanBase64.split(',')[1];
  }

  const promptText = `คุณคือระบบ AI OCR สกัดข้อมูลจากภาพถ่ายบัตรประชาชนไทย (Thai National ID Card)
โปรดดึงข้อมูลที่ปรากฏบนบัตรให้อยู่ในรูปแบบ JSON ตามโครงสร้างนี้:
{
  "id_card_number": "เลขประจำตัวประชาชน 13 หลัก (เฉพาะตัวเลข)",
  "title": "คำนำหน้า (เช่น นาย, นาง, น.ส.)",
  "first_name": "ชื่อตัวภาษาไทย",
  "last_name": "ชื่อสกุลภาษาไทย",
  "birth_date": "วันเดือนปีเกิดตามบัตร",
  "address": "ที่อยู่ตามบัตร (ตัดคำว่าที่อยู่ออก เอาเฉพาะรายละเอียด)"
}
ข้อกำหนด PDPA ที่สำคัญมาก:
- ไม่ต้องสกัดข้อมูลศาสนาและกรุ๊ปเลือดเด็ดขาด
- หากจุดไหนอ่านไม่ออกหรือไม่มี ให้เว้นเป็นค่าว่าง ""
- ตอบเฉพาะ JSON บริสุทธิ์ ห้ามใส่ markdown block`;

  const payload = {
    contents: [{
      parts: [
        { text: promptText },
        { inline_data: { mime_type: mimeType, data: cleanBase64 } }
      ]
    }],
    generationConfig: {
      response_mime_type: 'application/json',
      temperature: 0.1
    }
  };

  const candidateModels = ['gemini-2.0-flash', 'gemini-1.5-flash', 'gemini-2.5-flash', 'gemini-3.1-flash-lite'];
  let lastError = null;
  let resJson = null;

  for (const model of candidateModels) {
    try {
      const apiUrl = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${geminiKey}`;
      const response = await fetch(apiUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      if (response.ok) {
        resJson = await response.json();
        break;
      } else {
        const errorText = await response.text();
        if (errorText.includes('API_KEY_INVALID')) {
          localStorage.removeItem('VMS_GEMINI_KEY');
          throw new Error('Google แจ้งว่า API Key ไม่ถูกต้อง (API_KEY_INVALID) โปรดตรวจสอบคีย์อีกครั้ง');
        }
        lastError = new Error(`Model ${model} (${response.status}): ${errorText}`);
      }
    } catch (e) {
      lastError = e;
      if (e.message && e.message.includes('API_KEY_INVALID')) throw e;
    }
  }

  if (!resJson) {
    throw lastError || new Error('ไม่สามารถประมวลผล OCR ผ่าน Gemini AI ได้');
  }

  const candidateText = resJson.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!candidateText) throw new Error('ไม่พบข้อมูลตอบกลับจาก Gemini AI');

  let parsed = {};
  try {
    parsed = JSON.parse(candidateText.trim());
  } catch (e) {
    const cleanJson = candidateText.replace(/```json/g, '').replace(/```/g, '').trim();
    parsed = JSON.parse(cleanJson);
  }

  return {
    success: true,
    data: {
      id_card_number: (parsed.id_card_number || '').replace(/[^0-9]/g, ''),
      title: parsed.title || '',
      first_name: parsed.first_name || '',
      last_name: parsed.last_name || '',
      birth_date: parsed.birth_date || '',
      address: parsed.address || ''
    }
  };
}

/**
 * Universal Proxy Polyfill:
 * หากเปิดหน้าเว็บผ่าน GitHub Pages (หรือเปิดไฟล์ HTML โดยตรงที่ไม่มี google.script.run)
 * ตัว Proxy นี้จะแปลงคำสั่งให้ทำงานผ่าน Supabase REST API & Gemini API โดยตรงอัตโนมัติ 100%!
 */
if (typeof window !== 'undefined') {
  if (typeof window.google === 'undefined') {
    window.google = {};
  }
  if (!window.google.script || !window.google.script.run) {
    function createGasShimRunner(handlers) {
      return new Proxy({}, {
        get(target, propKey) {
          if (propKey === 'withSuccessHandler') {
            return function(fn) {
              return createGasShimRunner(Object.assign({}, handlers, { success: fn }));
            };
          }
          if (propKey === 'withFailureHandler') {
            return function(fn) {
              return createGasShimRunner(Object.assign({}, handlers, { failure: fn }));
            };
          }

          // เมื่อมีการเรียกฟังก์ชันฝั่ง Server
          return async function(...args) {
            try {
              let res = null;

              // 1. OCR บัตรประชาชน (เรียกผ่าน Google Apps Script เป็นหลักเพื่อเก็บคีย์ปลอดภัย 100% ใน GAS ไม่หลุดไป GitHub)
              if (propKey === 'extractIdCardWithGemini' || propKey === 'extractIdCard') {
                try {
                  res = await callGasApi('extractIdCard', {
                    base64Image: args[0],
                    mimeType: args[1] || 'image/jpeg'
                  });
                  if (!res || !res.success) {
                    throw new Error((res && res.message) ? res.message : 'GAS OCR unsuccessful');
                  }
                } catch (gasErr) {
                  console.warn('Google Apps Script Web App OCR unavailable or restricted (401), automatically falling back to client-side direct Gemini OCR...', gasErr);
                  // ใช้งาน directGeminiOcr ทันที (จะตรวจจับ API Key หรือแสดงหน้าต่างให้กรอกคีย์อัตโนมัติ)
                  res = await directGeminiOcr(args[0], args[1] || 'image/jpeg');
                }
              }

              // 2. บันทึกการเข้า (Check-In) ผ่าน Supabase ตรง
              else if (propKey === 'saveCheckIn') {
                const v = args[0] || {};
                const passCode = (v.pass_code || '').trim().toUpperCase();

                // เช็คว่าบัตรถูกใช้งานอยู่หรือไม่
                const active = await callSupabaseRest(`visitor_logs?pass_code=eq.${encodeURIComponent(passCode)}&status=eq.CHECKED_IN&select=id`);
                if (active && active.length > 0) {
                  throw new Error(`บัตรหมายเลข ${passCode} กำลังถูกใช้งานอยู่ ยังไม่ได้ทำรายการออก`);
                }

                // หากมีภาพถ่าย ให้บันทึกลง Google Drive ผ่าน GAS Web App
                let photoUrl = v.photo_base64 || null;
                if (photoUrl && photoUrl.length > 100 && !photoUrl.startsWith('http')) {
                  try {
                    const driveRes = await callGasApi('savePhotoToDrive', {
                      photo_base64: photoUrl,
                      id_card_number: v.id_card_number,
                      full_name: `${v.first_name || ''} ${v.last_name || ''}`.trim()
                    });
                    if (driveRes && driveRes.success && driveRes.fileUrl) {
                      photoUrl = driveRes.fileUrl;
                    }
                  } catch (driveErr) {
                    console.warn('Failed to upload photo to Drive, using base64:', driveErr);
                  }
                }

                let notes = v.guard_in_notes || '';
                if (v.name_en && !notes.includes('[EN:')) {
                  notes = (notes ? (notes + ' ') : '') + `[EN: ${v.name_en}]`;
                }

                // บันทึก log
                await callSupabaseRest('visitor_logs', 'POST', {
                  pass_code: passCode,
                  id_card_number: v.id_card_number || null,
                  title: v.title || null,
                  first_name: v.first_name || null,
                  last_name: v.last_name || null,
                  birth_date: v.birth_date || null,
                  address: v.address || null,
                  contact_person: v.contact_person || null,
                  department_or_house: v.department_or_house || v.contact_person || null,
                  license_plate: v.license_plate || null,
                  purpose: v.purpose || 'ติดต่อทั่วไป',
                  photo_base64: photoUrl,
                  guard_in_notes: notes || null,
                  status: 'CHECKED_IN',
                  check_in_at: new Date().toISOString(),
                  signature_status: 'PENDING'
                });

                // อัปเดตสถานะบัตรเป็น IN_USE
                await callSupabaseRest(`visitor_passes?pass_code=eq.${encodeURIComponent(passCode)}`, 'PATCH', {
                  status: 'IN_USE',
                  updated_at: new Date().toISOString()
                });

                res = {
                  success: true,
                  message: 'ลงทะเบียนเข้าสำเร็จ',
                  require_signature: true,
                  pass_code: passCode
                };
              }

              // 3. ค้นหาบัตรที่ค้างอยู่ (Check-Out) ผ่าน Supabase ตรง
              else if (propKey === 'findActiveVisitorByPassCode' || propKey === 'findActiveVisitor') {
                const passCode = (args[0] || '').trim().toUpperCase();
                const list = await callSupabaseRest(`visitor_logs?pass_code=eq.${encodeURIComponent(passCode)}&status=eq.CHECKED_IN&order=check_in_at.desc&limit=1&select=*`);
                if (list && list.length > 0) {
                  const item = list[0];
                  let nameEn = item.name_en || '';
                  if (!nameEn && item.guard_in_notes) {
                    const m = item.guard_in_notes.match(/\[EN:\s*([^\]]+)\]/i);
                    if (m) nameEn = m[1];
                  }
                  const nameTh = `${item.title || ''} ${item.first_name || ''} ${item.last_name || ''}`.trim() || 'ผู้มาติดต่อ';
                  const visitor = Object.assign({}, item, {
                    visitor_name: nameTh,
                    visitor_name_en: nameEn,
                    require_signature: true,
                    can_checkout: (item.signature_status === 'SIGNED')
                  });
                  res = { success: true, data: visitor };
                } else {
                  res = { success: false, message: `ไม่พบบันทึกการเข้าพื้นที่ของบัตร ${passCode} หรือถูกคืนบัตรไปแล้ว` };
                }
              }

              // 4. บันทึกการออก (Check-Out) ผ่าน Supabase ตรง
              else if (propKey === 'saveCheckOut') {
                const logId = args[0];
                const passCode = (args[1] || '').trim().toUpperCase();
                const notes = args[2] || '';

                // ตรวจสอบความปลอดภัย: ผู้รับการติดต่อต้องเซ็นรับรองแล้วเท่านั้น ถึงจะให้ออกได้
                const checkLogs = await callSupabaseRest(`visitor_logs?id=eq.${logId}&select=signature_status`);
                if (checkLogs && checkLogs.length > 0) {
                  const st = checkLogs[0].signature_status;
                  if (st !== 'SIGNED') {
                    throw new Error('ไม่อนุญาตให้ออก: ผู้รับการติดต่อยังไม่ได้ลงชื่อรับรองการเข้าพบ (กรุณาให้ผู้รับการติดต่อเซ็นชื่อก่อนคืนบัตร)');
                  }
                }

                await callSupabaseRest(`visitor_logs?id=eq.${logId}`, 'PATCH', {
                  status: 'CHECKED_OUT',
                  check_out_at: new Date().toISOString(),
                  guard_out_notes: notes || 'คืนบัตร'
                });

                await callSupabaseRest(`visitor_passes?pass_code=eq.${encodeURIComponent(passCode)}`, 'PATCH', {
                  status: 'AVAILABLE',
                  updated_at: new Date().toISOString()
                });

                res = { success: true, message: `รับคืนบัตร ${passCode} และบันทึกเวลาออกสำเร็จ` };
              }

              // 5. ดึงรายการบัตรที่ค้างอยู่สำหรับ รปภ.
              else if (propKey === 'getActiveVisitorsListForGuard') {
                const list = await callSupabaseRest('visitor_logs?status=eq.CHECKED_IN&order=check_in_at.desc&select=id,pass_code,title,first_name,last_name,guard_in_notes,contact_person,department_or_house,license_plate,check_in_at,signed_by,signature_status');
                const formatted = (list || []).map(v => {
                  let nameEn = v.name_en || '';
                  if (!nameEn && v.guard_in_notes) {
                    const m = v.guard_in_notes.match(/\[EN:\s*([^\]]+)\]/i);
                    if (m) nameEn = m[1];
                  }
                  return Object.assign({}, v, {
                    visitor_name: `${v.title || ''} ${v.first_name || ''} ${v.last_name || ''}`.trim() || 'ผู้มาติดต่อ',
                    visitor_name_en: nameEn
                  });
                });
                res = { success: true, data: formatted };
              }

              // 6. ดึงข้อมูลบัตรสำหรับหน้าเซ็นชื่อดิจิทัล
              else if (propKey === 'getVisitorPassForSigning') {
                const passCode = (args[0] || '').trim().toUpperCase();
                const list = await callSupabaseRest(`visitor_logs?pass_code=eq.${encodeURIComponent(passCode)}&status=eq.CHECKED_IN&order=check_in_at.desc&limit=1&select=*`);
                if (list && list.length > 0) {
                  const v = list[0];
                  const isSigned = (v.signature_status === 'SIGNED');
                  let nameEn = v.name_en || '';
                  if (!nameEn && v.guard_in_notes) {
                    const m = v.guard_in_notes.match(/\[EN:\s*([^\]]+)\]/i);
                    if (m) nameEn = m[1];
                  }
                  const nameTh = `${v.title || ''} ${v.first_name || ''} ${v.last_name || ''}`.trim() || 'ผู้มาติดต่อ';
                  res = {
                    success: true,
                    state: isSigned ? 'ALREADY_SIGNED' : 'PENDING',
                    data: {
                      id: v.id,
                      pass_code: v.pass_code,
                      visitor_name: nameTh,
                      visitor_name_en: nameEn,
                      department_or_house: v.department_or_house || v.contact_person || '-',
                      license_plate: v.license_plate || '-',
                      purpose: v.purpose || 'ติดต่อทั่วไป',
                      photo_base64: v.photo_base64 || '',
                      check_in_at: v.check_in_at,
                      signature_status: v.signature_status || 'PENDING',
                      signed_by: v.signed_by || '',
                      signed_at: v.signed_at || null,
                      signature_url: v.signature_url || ''
                    }
                  };
                } else {
                  res = {
                    success: true,
                    state: 'NOT_ACTIVE',
                    pass_code: passCode,
                    message: `บัตรหมายเลข "${passCode}" ยังไม่ได้ลงทะเบียนเข้า หรือคืนบัตรออกจากพื้นที่แล้ว`
                  };
                }
              }

              // 7. บันทึกลายเซ็นดิจิทัล
              else if (propKey === 'submitHostSignature') {
                const passCode = (args[0] || '').trim().toUpperCase();
                const hostName = args[1] || '';
                const sigBase64 = args[2] || '';
                const remarks = args[3] || '';

                // ลองส่งบันทึกลง Google Drive ผ่าน GAS ก่อน (ถ้า GAS พร้อมใช้งาน)
                let driveUrl = null;
                try {
                  const gasRes = await callGasApi('submitHostSignature', {
                    passCode: passCode,
                    hostName: hostName,
                    signatureBase64: sigBase64,
                    remarks: remarks
                  });
                  if (gasRes && gasRes.success) {
                    res = gasRes;
                    if (handlers.success) handlers.success(res);
                    return res;
                  }
                } catch (gasErr) {
                  console.warn('GAS Drive upload skipped, saving direct to Supabase:', gasErr);
                }

                // Fallback: อัปเดตลง Supabase โดยตรง (เก็บ Base64 หรือ URL)
                const nowIso = new Date().toISOString();
                const activeList = await callSupabaseRest(`visitor_logs?pass_code=eq.${encodeURIComponent(passCode)}&status=eq.CHECKED_IN&order=check_in_at.desc&limit=1&select=id`);
                if (!activeList || activeList.length === 0) {
                  throw new Error(`ไม่พบบันทึกที่กำลังเข้าพื้นที่ของบัตร ${passCode}`);
                }

                await callSupabaseRest(`visitor_logs?id=eq.${activeList[0].id}`, 'PATCH', {
                  signature_status: 'SIGNED',
                  signed_by: hostName,
                  signed_at: nowIso,
                  signature_url: driveUrl || sigBase64,
                  guard_in_notes: remarks ? `[ผู้รับรอง]: ${remarks}` : undefined
                });

                res = { success: true, message: `บันทึกลายเซ็นรับรองบัตร ${passCode} เรียบร้อยแล้ว` };
              }

              // 8. ดึงข้อมูลแดชบอร์ด Admin
              else if (propKey === 'getAdminOverviewStats' || propKey === 'getAdminDashboardStats') {
                const allPasses = await callSupabaseRest('visitor_passes?select=pass_code,status') || [];
                const active = await callSupabaseRest('visitor_logs?status=eq.CHECKED_IN&order=check_in_at.desc&select=*') || [];
                
                const now = new Date();
                const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).toISOString();
                const todayLogs = await callSupabaseRest(`visitor_logs?check_in_at=gte.${todayStart}&select=id,status`) || [];

                res = {
                  success: true,
                  stats: {
                    totalToday: todayLogs.length,
                    activeNow: active.length,
                    checkedOutToday: todayLogs.filter(l => l.status === 'CHECKED_OUT').length,
                    availablePasses: allPasses.filter(p => p.status === 'AVAILABLE').length,
                    totalPasses: allPasses.length
                  },
                  activeList: active
                };
              }

              // 9. ดึงประวัติผู้มาติดต่อทั้งหมดสำหรับ Admin
              else if (propKey === 'getAdminVisitorLogs' || propKey === 'getAdminLogs') {
                const dateRange = args[0] || 'TODAY';
                let filter = '';
                const now = new Date();
                if (dateRange === 'TODAY') {
                  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).toISOString();
                  filter = `&check_in_at=gte.${todayStart}`;
                } else if (dateRange === 'LAST_7_DAYS') {
                  const past7 = new Date(now.getTime() - (7 * 24 * 60 * 60 * 1000)).toISOString();
                  filter = `&check_in_at=gte.${past7}`;
                } else if (dateRange === 'THIS_MONTH') {
                  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1).toISOString();
                  filter = `&check_in_at=gte.${monthStart}`;
                }
                const logs = await callSupabaseRest(`visitor_logs?order=check_in_at.desc&limit=250${filter}&select=*`);
                res = { success: true, data: logs || [] };
              }

              // 10. ดึงรายการบัตรทั้งหมดสำหรับ Admin พร้อมแนบข้อมูลผู้มาติดต่อที่ยังอยู่ในพื้นที่
              else if (propKey === 'getAdminPassesList') {
                const passes = (await callSupabaseRest('visitor_passes?order=pass_code.asc&select=*')) || [];
                const activeLogs = (await callSupabaseRest('visitor_logs?status=eq.CHECKED_IN&select=pass_code,title,first_name,last_name,guard_in_notes,department_or_house,contact_person,license_plate,purpose,photo_base64,check_in_at')) || [];

                const activeMap = {};
                activeLogs.forEach(l => {
                  const nameTh = `${l.title || ''} ${l.first_name || ''} ${l.last_name || ''}`.trim() || 'ผู้มาติดต่อ';
                  let nameEn = l.name_en || '';
                  if (!nameEn && l.guard_in_notes) {
                    const m = l.guard_in_notes.match(/\[EN:\s*([^\]]+)\]/i);
                    if (m) nameEn = m[1];
                  }
                  activeMap[l.pass_code] = {
                    visitor_name: nameTh,
                    visitor_name_en: nameEn,
                    department_or_house: l.department_or_house || l.contact_person || '-',
                    license_plate: l.license_plate || '-',
                    purpose: l.purpose || 'ติดต่อทั่วไป',
                    photo_base64: l.photo_base64 || '',
                    check_in_at: l.check_in_at
                  };
                });

                passes.forEach(p => {
                  if (activeMap[p.pass_code]) {
                    p.visitor_name = activeMap[p.pass_code].visitor_name;
                    p.visitor_name_en = activeMap[p.pass_code].visitor_name_en;
                    p.department_or_house = activeMap[p.pass_code].department_or_house;
                    p.license_plate = activeMap[p.pass_code].license_plate;
                    p.purpose = activeMap[p.pass_code].purpose;
                    p.photo_base64 = activeMap[p.pass_code].photo_base64;
                    p.check_in_at = activeMap[p.pass_code].check_in_at;
                  }
                });

                res = { success: true, data: passes };
              }

              // 11. ดึงการตั้งค่า Admin
              else if (propKey === 'getSystemConfigForAdmin' || propKey === 'getSystemConfig') {
                const reqSign = localStorage.getItem('VMS_REQUIRE_SIGNATURE') === 'true';
                const gemKey = VMS_CONFIG.GEMINI_API_KEY || localStorage.getItem('VMS_GEMINI_KEY') || '';
                res = {
                  supabaseUrl: VMS_CONFIG.SUPABASE_URL,
                  hasSupabaseKey: true,
                  hasGeminiKey: !!gemKey,
                  activeModel: 'gemini-3.1-flash-lite',
                  requireHostSignature: reqSign
                };
              }

              // 12. บันทึกการตั้งค่า Admin
              else if (propKey === 'saveSystemConfig') {
                const cfg = args[0] || {};
                if (typeof cfg.requireHostSignature !== 'undefined') {
                  localStorage.setItem('VMS_REQUIRE_SIGNATURE', cfg.requireHostSignature ? 'true' : 'false');
                }
                if (cfg.geminiKey && !cfg.geminiKey.includes('********')) {
                  localStorage.setItem('VMS_GEMINI_KEY', cfg.geminiKey.trim());
                }
                res = { success: true, message: 'บันทึกการตั้งค่าระบบเรียบร้อยแล้ว' };
              }

              // 13. ทดสอบการเชื่อมต่อ Supabase
              else if (propKey === 'testSupabaseConnection' || propKey === 'testSupabase') {
                await callSupabaseRest('visitor_passes?limit=1&select=pass_code');
                res = { success: true, message: 'เชื่อมต่อ Supabase สำเร็จ พร้อมใช้งาน 100%!' };
              }

              // 14. ทดสอบการเชื่อมต่อ Gemini AI
              else if (propKey === 'testGeminiConnection' || propKey === 'testGemini') {
                const testPrompt = { contents: [{ parts: [{ text: "ตอบสั้นๆ ว่า OK" }] }] };
                let k = VMS_CONFIG.GEMINI_API_KEY || localStorage.getItem('VMS_GEMINI_KEY');
                if (!k) throw new Error('ยังไม่ได้กำหนด Gemini API Key');
                const testUrl = `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=${k}`;
                const testResp = await fetch(testUrl, {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify(testPrompt)
                });
                if (!testResp.ok) throw new Error(`Gemini Error (${testResp.status})`);
                res = { success: true, message: 'โมเดล gemini-3.1-flash-lite พร้อมใช้งาน 100%!' };
              }

              // 15. ปลดล็อคบัตรให้ว่าง
              else if (propKey === 'adminForceResetPass') {
                const code = (args[0] || '').trim().toUpperCase();
                await callSupabaseRest(`visitor_passes?pass_code=eq.${encodeURIComponent(code)}`, 'PATCH', {
                  status: 'AVAILABLE',
                  updated_at: new Date().toISOString()
                });
                await callSupabaseRest(`visitor_logs?pass_code=eq.${encodeURIComponent(code)}&status=eq.CHECKED_IN`, 'PATCH', {
                  status: 'CHECKED_OUT',
                  check_out_at: new Date().toISOString(),
                  guard_out_notes: 'ผู้ดูแลระบบบังคับปลดล็อค'
                });
                res = { success: true, message: `ปลดล็อคบัตร ${code} เรียบร้อยแล้ว` };
              }

              // 16. บังคับเช็คเอาท์
              else if (propKey === 'adminForceCheckout') {
                const logId = args[0];
                const code = (args[1] || '').trim().toUpperCase();
                await callSupabaseRest(`visitor_logs?id=eq.${logId}`, 'PATCH', {
                  status: 'CHECKED_OUT',
                  check_out_at: new Date().toISOString(),
                  guard_out_notes: 'ผู้ดูแลระบบบังคับเช็คเอาท์'
                });
                await callSupabaseRest(`visitor_passes?pass_code=eq.${encodeURIComponent(code)}`, 'PATCH', {
                  status: 'AVAILABLE',
                  updated_at: new Date().toISOString()
                });
                res = { success: true, message: `เช็คเอาท์บัตร ${code} เรียบร้อยแล้ว` };
              }

              // 17. สร้างชุดบัตรใหม่
              else if (propKey === 'adminAddNewPasses') {
                const prefix = args[0] || 'V-';
                const start = parseInt(args[1]);
                const end = parseInt(args[2]);
                const newRows = [];
                for (let i = start; i <= end; i++) {
                  const numStr = (i < 10 ? '00' : (i < 100 ? '0' : '')) + i;
                  newRows.push({
                    pass_code: `${prefix}${numStr}`,
                    status: 'AVAILABLE',
                    created_at: new Date().toISOString(),
                    updated_at: new Date().toISOString()
                  });
                }
                await callSupabaseRest('visitor_passes', 'POST', newRows, { 'Prefer': 'resolution=ignore-duplicates' });
                res = { success: true, message: `สร้างบัตร ${prefix}${start} ถึง ${prefix}${end} สำเร็จ` };
              }

              // 18. ดึงรายชื่อแผนก/จุดเข้าพบ (Visitor Departments)
              else if (propKey === 'getVisitorDepartments') {
                const defaultDepts = [
                  'ฝ่ายบริหาร / ผู้บริหาร',
                  'ฝ่ายทรัพยากรบุคคล (HR)',
                  'ฝ่ายจัดซื้อและพัสดุ',
                  'ฝ่ายบัญชีและการเงิน',
                  'ฝ่ายขายและการตลาด',
                  'ฝ่ายเทคโนโลยีสารสนเทศ (IT)',
                  'ฝ่ายคลังสินค้า / สโตร์ / โลจิสติกส์',
                  'ฝ่ายผลิตและซ่อมบำรุง',
                  'แผนกต้อนรับ / ประชาสัมพันธ์',
                  'งานรักษาความปลอดภัย / ก่อสร้าง',
                  'บ้านพัก / ห้องชุด / ที่อยู่อาศัย'
                ];
                try {
                  const data = await callSupabaseRest('visitor_departments?select=department&order=department.asc');
                  if (data && Array.isArray(data) && data.length > 0) {
                    res = { success: true, data: data.map(d => d.department || d.departments).filter(Boolean) };
                  } else {
                    res = { success: true, data: defaultDepts };
                  }
                } catch (e) {
                  console.warn('Could not load visitor_departments, using defaults:', e);
                  res = { success: true, data: defaultDepts };
                }
              }

              // 19. เข้าสู่ระบบผู้ดูแลระบบ (Admin Login)
              else if (propKey === 'adminLogin') {
                const username = (args[0] || '').trim();
                const password = (args[1] || '').trim();
                if (!username || !password) {
                  throw new Error('กรุณาระบุชื่อผู้ใช้และรหัสผ่าน');
                }

                let authenticatedUser = null;
                // 1. ลองตรวจสอบจาก Supabase
                try {
                  const users = await callSupabaseRest(`admin_users?username=eq.${encodeURIComponent(username)}&password=eq.${encodeURIComponent(password)}&select=id,username,email`);
                  if (users && users.length > 0) {
                    authenticatedUser = { username: users[0].username, email: users[0].email };
                  }
                } catch (dbErr) {
                  console.warn('Supabase admin_users query failed, falling back to local users:', dbErr);
                }

                // 2. ตรวจสอบใน LocalStorage หรือ Default Admin Account
                if (!authenticatedUser) {
                  let localUsers = [];
                  try {
                    localUsers = JSON.parse(localStorage.getItem('VMS_ADMIN_USERS_LOCAL') || '[]');
                  } catch (e) {}

                  const found = localUsers.find(u => u.username === username && u.password === password);
                  if (found) {
                    authenticatedUser = { username: found.username, email: found.email };
                  } else if (username === 'admin' && (password === '1234' || password === 'admin')) {
                    authenticatedUser = { username: 'admin', email: 'admin@vms.local' };
                  }
                }

                if (authenticatedUser) {
                  res = { success: true, user: authenticatedUser };
                } else {
                  res = { success: false, message: 'ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง' };
                }
              }

              // 20. ลงทะเบียนผู้ดูแลระบบใหม่ (Admin Register)
              else if (propKey === 'adminRegisterUser') {
                const username = (args[0] || '').trim();
                const password = (args[1] || '').trim();
                const email = (args[2] || '').trim();

                if (!username || !password || !email) {
                  throw new Error('กรุณากรอกข้อมูลให้ครบถ้วน (ชื่อผู้ใช้, รหัสผ่าน, อีเมล)');
                }

                // บันทึกสำรองใน LocalStorage เสมอ
                let localUsers = [];
                try {
                  localUsers = JSON.parse(localStorage.getItem('VMS_ADMIN_USERS_LOCAL') || '[]');
                } catch (e) {}

                if (localUsers.some(u => u.username.toLowerCase() === username.toLowerCase())) {
                  throw new Error('ชื่อผู้ใช้นี้มีอยู่ในระบบแล้ว');
                }

                localUsers.push({ username, password, email, created_at: new Date().toISOString() });
                localStorage.setItem('VMS_ADMIN_USERS_LOCAL', JSON.stringify(localUsers));

                // พยายามบันทึกลง Supabase
                try {
                  await callSupabaseRest('admin_users', 'POST', { username, password, email });
                } catch (dbErr) {
                  console.warn('Could not save to Supabase admin_users table:', dbErr);
                }

                res = { success: true, message: 'ลงทะเบียนผู้ดูแลระบบเรียบร้อยแล้ว', user: { username, email } };
              }

              if (handlers.success) handlers.success(res);
              return res;
            } catch (err) {
              if (handlers.failure) handlers.failure(err);
              else console.error('API Error:', err);
              throw err;
            }
          };
        }
      });
    }

    window.google.script = {
      run: createGasShimRunner({ success: null, failure: null })
    };
  }
}
