-- 种子数据（幂等，可重复执行；不会覆盖运行期产生的数据）
-- 口径约定：抑制阈值 = 计数 < 4 的组不予发布（最小发布组 4），并执行互补抑制

-- ============ 账号 ============
INSERT INTO app_user(username, display_name, password, role, home_district, clearance, allowed_purposes) VALUES
 ('city_admin','市级管理员','city123','CITY_ADMIN',NULL,'RESTRICTED','{"STAT","CASE_INVEST","AUDIT","GOVERN"}'),
 ('jia_analyst','甲区分析员','jia123','DISTRICT_ANALYST','JIA','INTERNAL','{"STAT"}'),
 ('yi_analyst','乙区分析员','yi123','DISTRICT_ANALYST','YI','INTERNAL','{"STAT","CASE_INVEST"}'),
 ('auditor','审计员','audit123','AUDITOR',NULL,'RESTRICTED','{"AUDIT"}')
ON CONFLICT DO NOTHING;

INSERT INTO user_case_grant(username, district, case_id, purpose) VALUES
 ('jia_analyst','JIA','*','STAT'),
 ('yi_analyst','YI','*','STAT'),
 ('yi_analyst','YI','*','CASE_INVEST'),
 ('yi_analyst','JIA','J-2026-003','CASE_INVEST'),
 ('jia_analyst','BING','*','STAT')
ON CONFLICT DO NOTHING;

-- ============ 目录版本 ============
INSERT INTO field_catalog_version(version, note) VALUES
 (1,'2025 初版目录'),
 (2,'2026-01 乙区风险代码重定义（代码2改为需观察）'),
 (3,'2026-06 烟气密度单位口径调整（ug/m³）')
ON CONFLICT DO NOTHING;

-- ============ 字段定义（同 field_id 按版本多行） ============
-- 市级标准字段
INSERT INTO field_def(field_id,version,display_name,definition,value_domain,unit,granularity,event_time_meaning,applicable_districts,sensitivity,valid_from,status,namespace) VALUES
 ('std.hazard_level',1,'隐患等级（标准）','市级统一的隐患等级语义，由各辖区代码按证据映射得到','{"type":"enum","values":["MAJOR","LIMITED","OBSERVE","RESCINDED"]}',NULL,'案件-复查级','等级判定/复查事件的发生时间','{}','INTERNAL','2025-01-01','ACTIVE','CITY'),
 ('std.crack_width_mm',1,'裂缝宽度（标准，毫米）','构件裂缝宽度的标准化测量值，统一毫米','{"type":"numeric","min":0,"max":500}','mm','单次测量','裂缝测量时刻','{}','INTERNAL','2025-01-01','ACTIVE','CITY'),
 ('std.rectification_status',1,'整改状态（标准）','案件当前整改状态，由整改事件流重放得到','{"type":"enum","values":["OPEN","RECTIFIED"]}',NULL,'案件级','最近一次整改事件时间','{}','INTERNAL','2025-01-01','ACTIVE','CITY'),
 ('std.smoke_density',1,'烟气密度（旧口径）','烟气浓度，旧版以 mg/m³ 计','{"type":"numeric","min":0,"max":10000}','mg/m3','单次测量','采样时刻','{}','INTERNAL','2025-01-01','DEPRECATED','CITY'),
 ('std.smoke_density',3,'烟气密度（新口径）','烟气浓度，调整为 ug/m³，数值口径同步换算，且纳入敏感管控','{"type":"numeric","min":0,"max":10000000}','ug/m3','单次测量','采样时刻','{}','RESTRICTED','2026-06-01','ACTIVE','CITY')
ON CONFLICT DO NOTHING;

-- 甲区命名空间字段（裂缝直接采用市级标准字段，体现“市级统一维护、表单直接采用”路线）
INSERT INTO field_def(field_id,version,display_name,definition,value_domain,unit,granularity,event_time_meaning,applicable_districts,sensitivity,valid_from,status,namespace) VALUES
 ('jia.risk_level',1,'甲区风险代码','甲区表单代码：1=重大隐患，2=需限期整改，3=已撤销','{"type":"enum","values":["1","2","3"]}',NULL,'案件-复查级','代码填报对应的检查/复查时间','{"JIA"}','INTERNAL','2025-01-01','ACTIVE','JIA'),
 ('jia.rectified',1,'甲区是否已整改','表单勾选的整改结论','{"type":"enum","values":["true","false"]}',NULL,'案件级','整改确认时间','{"JIA"}','INTERNAL','2025-01-01','ACTIVE','JIA')
ON CONFLICT DO NOTHING;

-- 乙区字段：v1 旧语义、v2 新语义（同名不同义，按版本并存，不自动合并）
INSERT INTO field_def(field_id,version,display_name,definition,value_domain,unit,granularity,event_time_meaning,applicable_districts,sensitivity,valid_from,status,namespace) VALUES
 ('yi.risk_level',1,'乙区风险代码（旧版）','旧版手册：1=需限期整改，2=重大隐患，3=已撤销','{"type":"enum","values":["1","2","3"]}',NULL,'案件-复查级','代码填报对应的检查时间','{"YI"}','INTERNAL','2025-01-01','DEPRECATED','YI'),
 ('yi.risk_level',2,'乙区风险代码（新版）','新版表单填报说明：1=重大隐患，2=需观察，3=已撤销，4=临时管控（暂无市级等价语义）','{"type":"enum","values":["1","2","3","4"]}',NULL,'案件-复查级','代码填报对应的检查/复查时间','{"YI"}','INTERNAL','2026-01-01','ACTIVE','YI'),
 ('yi.crack_width_cm',1,'乙区裂缝宽度（厘米）','乙区表单以厘米记录的裂缝宽度，需换算为毫米','{"type":"numeric","min":0,"max":50}','cm','单次测量','裂缝测量时刻','{"YI"}','INTERNAL','2025-01-01','ACTIVE','YI'),
 ('yi.rectified',1,'乙区是否已整改','表单勾选的整改结论','{"type":"enum","values":["true","false"]}',NULL,'案件级','整改确认时间','{"YI"}','INTERNAL','2025-01-01','ACTIVE','YI')
ON CONFLICT DO NOTHING;
-- 注：yi.ev_charge_hazard 为待批准的辖区扩展字段，批准前不进入 field_def（治理界面批准时随目录 v4 写入）

-- 丙区敏感字段（RESTRICTED，需相应密级 + 目的）
INSERT INTO field_def(field_id,version,display_name,definition,value_domain,unit,granularity,event_time_meaning,applicable_districts,sensitivity,valid_from,status,namespace) VALUES
 ('bing.hazard_level',1,'丙区隐患代码（敏感）','丙区代码：A=重大 B=限期整改 C=观察 D=撤销','{"type":"enum","values":["A","B","C","D"]}',NULL,'案件-复查级','等级判定事件时间','{"BING"}','RESTRICTED','2025-01-01','ACTIVE','BING'),
 ('bing.crack_width_mm',1,'丙区裂缝宽度（敏感，毫米）','丙区毫米制裂缝宽度','{"type":"numeric","min":0,"max":500}','mm','单次测量','测量时刻','{"BING"}','RESTRICTED','2025-01-01','ACTIVE','BING'),
 ('bing.rectified',1,'丙区是否已整改（敏感）','丙区整改结论','{"type":"enum","values":["true","false"]}',NULL,'案件级','整改确认时间','{"BING"}','RESTRICTED','2025-01-01','ACTIVE','BING')
ON CONFLICT DO NOTHING;

-- ============ 映射契约 ============
INSERT INTO mapping_contract(contract_id,source_field_id,target_field_id,source_district,version,status,effective_from,effective_to,evidence_note,reviewed_by) VALUES
 ('MC-JIA-RISK','jia.risk_level','std.hazard_level','JIA',1,'APPROVED','2025-01-01',NULL,'甲区检查规范（2025）第5条代码表，逐值比对市标','city_admin'),
 ('MC-JIA-RECT','jia.rectified','std.rectification_status','JIA',1,'APPROVED','2025-01-01',NULL,'甲区表单整改勾选项与市标整改状态直接对应','city_admin'),
 ('MC-YI-RISK-V1','yi.risk_level','std.hazard_level','YI',1,'SUPERSEDED','2020-01-01','2025-12-31','乙区旧版填报手册（2020版），仅对旧版表单生效','city_admin'),
 ('MC-YI-RISK-V2','yi.risk_level','std.hazard_level','YI',2,'APPROVED','2026-01-01',NULL,'乙区新版表单填报说明（2026版），代码2含义已改为需观察；代码4无市级等价','city_admin'),
 ('MC-YI-CRACK','yi.crack_width_cm','std.crack_width_mm','YI',1,'APPROVED','2025-01-01',NULL,'计量单位换算证据：1 cm = 10 mm','city_admin'),
 ('MC-YI-RECT','yi.rectified','std.rectification_status','YI',1,'APPROVED','2025-01-01',NULL,'乙区表单整改勾选项与市标整改状态直接对应','city_admin'),
 ('MC-BING-HAZARD','bing.hazard_level','std.hazard_level','BING',1,'APPROVED','2025-01-01',NULL,'丙区代码表与市标逐值比对（敏感件，限授权目的）','city_admin'),
 ('MC-BING-CRACK','bing.crack_width_mm','std.crack_width_mm','BING',1,'APPROVED','2025-01-01',NULL,'丙区毫米制与市标单位一致，x*1','city_admin'),
 ('MC-BING-RECT','bing.rectified','std.rectification_status','BING',1,'APPROVED','2025-01-01',NULL,'丙区整改勾选与市标直接对应','city_admin'),
 ('MC-YI-EV','yi.ev_charge_hazard','std.ev_charge_hazard','YI',1,'DRAFT','2026-05-01',NULL,'乙区电动车充电隐患治理申请附件（现场照片+法条依据），待市级批准',NULL)
ON CONFLICT DO NOTHING;

-- 逐值映射（同名不自动合并，每个取值都要有证据与等价判定）
INSERT INTO mapping_value(contract_id,source_value,target_value,transform,evidence,equivalent) VALUES
 ('MC-JIA-RISK','1','MAJOR',NULL,'甲区规范第5.1条：代码1=重大隐患',TRUE),
 ('MC-JIA-RISK','2','LIMITED',NULL,'甲区规范第5.2条：代码2=需限期整改（非重大）',TRUE),
 ('MC-JIA-RISK','3','RESCINDED',NULL,'甲区规范第5.3条：代码3=已撤销',TRUE),
 ('MC-JIA-RECT','true','RECTIFIED',NULL,'勾选已整改对应 RECTIFIED',TRUE),
 ('MC-JIA-RECT','false','OPEN',NULL,'未勾选对应 OPEN',TRUE),
 ('MC-YI-RISK-V1','1','LIMITED',NULL,'乙区2020版手册：代码1=需限期整改',TRUE),
 ('MC-YI-RISK-V1','2','MAJOR',NULL,'乙区2020版手册：代码2=重大隐患（旧版语义，仅旧表单）',TRUE),
 ('MC-YI-RISK-V1','3','RESCINDED',NULL,'乙区2020版手册：代码3=已撤销',TRUE),
 ('MC-YI-RISK-V2','1','MAJOR',NULL,'乙区2026版说明第3条：代码1=重大隐患',TRUE),
 ('MC-YI-RISK-V2','2','OBSERVE',NULL,'乙区2026版表单填报说明：代码2含义为“需观察”，不得按同名计入重大',TRUE),
 ('MC-YI-RISK-V2','3','RESCINDED',NULL,'乙区2026版说明第3条：代码3=已撤销',TRUE),
 ('MC-YI-RISK-V2','4',NULL,NULL,'代码4=临时管控，市级目录无对应语义，无法证明语义等价，编译期拒绝',FALSE),
 ('MC-YI-CRACK','*',NULL,'x*10','厘米→毫米换算：标准值 = 源值 × 10',TRUE),
 ('MC-YI-RECT','true','RECTIFIED',NULL,'勾选已整改对应 RECTIFIED',TRUE),
 ('MC-YI-RECT','false','OPEN',NULL,'未勾选对应 OPEN',TRUE),
 ('MC-BING-HAZARD','A','MAJOR',NULL,'丙区代码表 A=重大隐患',TRUE),
 ('MC-BING-HAZARD','B','LIMITED',NULL,'丙区代码表 B=限期整改',TRUE),
 ('MC-BING-HAZARD','C','OBSERVE',NULL,'丙区代码表 C=观察',TRUE),
 ('MC-BING-HAZARD','D','RESCINDED',NULL,'丙区代码表 D=撤销',TRUE),
 ('MC-BING-CRACK','*',NULL,'x*1','单位均为毫米，恒等换算',TRUE),
 ('MC-BING-RECT','true','RECTIFIED',NULL,'勾选已整改对应 RECTIFIED',TRUE),
 ('MC-BING-RECT','false','OPEN',NULL,'未勾选对应 OPEN',TRUE),
 ('MC-YI-EV','Y','PRESENT',NULL,'现场照片与《高层民用建筑消防安全管理规定》依据，判定语义等价（待批准）',TRUE),
 ('MC-YI-EV','N','ABSENT',NULL,'检查记录未发现且无复查翻案，判定等价（待批准）',TRUE)
ON CONFLICT DO NOTHING;

-- ============ 表单模板（提交即固定目录版本） ============
INSERT INTO form_template(template_id,district,catalog_version,display_name) VALUES
 ('TPL-JIA','JIA',2,'甲区检查表（2026版）'),
 ('TPL-JIA-V3','JIA',3,'甲区检查表（2026-06版，基线=当前目录）'),
 ('TPL-YI-OLD','YI',1,'乙区检查表（2020旧版）'),
 ('TPL-YI-NEW','YI',2,'乙区检查表（2026新版）'),
 ('TPL-BING','BING',2,'丙区敏感检查表')
ON CONFLICT DO NOTHING;

INSERT INTO form_template_field(template_id,field_id,ordinal) VALUES
 ('TPL-JIA','std.crack_width_mm',1),('TPL-JIA','jia.risk_level',2),('TPL-JIA','jia.rectified',3),
 ('TPL-JIA-V3','std.crack_width_mm',1),('TPL-JIA-V3','jia.risk_level',2),('TPL-JIA-V3','jia.rectified',3),
 ('TPL-YI-OLD','yi.crack_width_cm',1),('TPL-YI-OLD','yi.risk_level',2),('TPL-YI-OLD','yi.rectified',3),
 ('TPL-YI-NEW','yi.crack_width_cm',1),('TPL-YI-NEW','yi.risk_level',2),('TPL-YI-NEW','yi.rectified',3),('TPL-YI-NEW','yi.ev_charge_hazard',4),
 ('TPL-BING','bing.crack_width_mm',1),('TPL-BING','bing.hazard_level',2),('TPL-BING','bing.rectified',3)
ON CONFLICT DO NOTHING;

-- ============ 表单实例（不可变，content_hash 固定；回溯映射不改写） ============
INSERT INTO form_instance(instance_id,case_id,template_id,district,catalog_version,inspect_date,submitter,content_hash,origin) VALUES
 ('FI-J-001','J-2026-001','TPL-JIA','JIA',2,'2026-03-05','jia_analyst','seed:FI-J-001','ONLINE'),
 ('FI-J-002','J-2026-002','TPL-JIA','JIA',2,'2026-02-10','jia_analyst','seed:FI-J-002','ONLINE'),
 ('FI-J-003','J-2026-003','TPL-JIA','JIA',2,'2026-03-12','jia_analyst','seed:FI-J-003','ONLINE'),
 ('FI-J-004','J-2026-004','TPL-JIA','JIA',2,'2026-03-15','jia_analyst','seed:FI-J-004','ONLINE'),
 ('FI-J-005','J-2026-005','TPL-JIA','JIA',2,'2026-04-02','jia_analyst','seed:FI-J-005','ONLINE'),
 ('FI-J-006','J-2026-006','TPL-JIA','JIA',2,'2026-04-18','jia_analyst','seed:FI-J-006','ONLINE'),
 ('FI-J-007','J-2026-007','TPL-JIA','JIA',2,'2026-04-20','jia_analyst','seed:FI-J-007','ONLINE'),
 ('FI-J-008','J-2026-008','TPL-JIA','JIA',2,'2026-03-22','jia_analyst','seed:FI-J-008','ONLINE'),
 ('FI-Y-001','Y-2026-001','TPL-YI-NEW','YI',2,'2026-03-08','yi_analyst','seed:FI-Y-001','ONLINE'),
 ('FI-Y-002','Y-2026-002','TPL-YI-OLD','YI',1,'2025-11-10','yi_analyst','seed:FI-Y-002','ONLINE'),
 ('FI-Y-003','Y-2026-003','TPL-YI-NEW','YI',2,'2026-03-19','yi_analyst','seed:FI-Y-003','ONLINE'),
 ('FI-Y-004','Y-2026-004','TPL-YI-NEW','YI',2,'2026-04-01','yi_analyst','seed:FI-Y-004','ONLINE'),
 ('FI-Y-005','Y-2026-005','TPL-YI-NEW','YI',2,'2026-05-12','yi_analyst','seed:FI-Y-005','ONLINE'),
 ('FI-B-001','B-2026-001','TPL-BING','BING',2,'2026-03-10','city_admin','seed:FI-B-001','ONLINE'),
 ('FI-B-002','B-2026-002','TPL-BING','BING',2,'2026-03-16','city_admin','seed:FI-B-002','ONLINE'),
 ('FI-B-003','B-2026-003','TPL-BING','BING',2,'2026-04-05','city_admin','seed:FI-B-003','ONLINE')
ON CONFLICT DO NOTHING;

INSERT INTO form_value(instance_id,field_id,value_text) VALUES
 ('FI-J-001','std.crack_width_mm','12'),('FI-J-001','jia.risk_level','2'),('FI-J-001','jia.rectified','false'),
 ('FI-J-002','std.crack_width_mm','15'),('FI-J-002','jia.risk_level','1'),('FI-J-002','jia.rectified','false'),
 ('FI-J-003','std.crack_width_mm','18'),('FI-J-003','jia.risk_level','1'),('FI-J-003','jia.rectified','false'),
 ('FI-J-004','std.crack_width_mm','11'),('FI-J-004','jia.risk_level','1'),('FI-J-004','jia.rectified','false'),
 ('FI-J-005','std.crack_width_mm','20'),('FI-J-005','jia.risk_level','1'),('FI-J-005','jia.rectified','false'),
 ('FI-J-006','std.crack_width_mm','22'),('FI-J-006','jia.risk_level','1'),('FI-J-006','jia.rectified','false'),
 ('FI-J-007','std.crack_width_mm','8'), ('FI-J-007','jia.risk_level','1'),('FI-J-007','jia.rectified','false'),
 ('FI-J-008','std.crack_width_mm','14'),('FI-J-008','jia.risk_level','1'),('FI-J-008','jia.rectified','false'),
 ('FI-Y-001','yi.crack_width_cm','1.2'),('FI-Y-001','yi.risk_level','2'),('FI-Y-001','yi.rectified','false'),
 ('FI-Y-002','yi.crack_width_cm','2.0'),('FI-Y-002','yi.risk_level','2'),('FI-Y-002','yi.rectified','false'),
 ('FI-Y-003','yi.crack_width_cm','1.5'),('FI-Y-003','yi.risk_level','1'),('FI-Y-003','yi.rectified','false'),
 ('FI-Y-004','yi.crack_width_cm','3.0'),('FI-Y-004','yi.risk_level','4'),('FI-Y-004','yi.rectified','false'),
 ('FI-Y-005','yi.crack_width_cm','1.6'),('FI-Y-005','yi.risk_level','1'),('FI-Y-005','yi.rectified','false'),('FI-Y-005','yi.ev_charge_hazard','Y'),
 ('FI-B-001','bing.crack_width_mm','12'),('FI-B-001','bing.hazard_level','A'),('FI-B-001','bing.rectified','false'),
 ('FI-B-002','bing.crack_width_mm','16'),('FI-B-002','bing.hazard_level','A'),('FI-B-002','bing.rectified','false'),
 ('FI-B-003','bing.crack_width_mm','9'), ('FI-B-003','bing.hazard_level','B'),('FI-B-003','bing.rectified','false')
ON CONFLICT DO NOTHING;

-- ============ 案件事件流（等级/整改状态按事件时间重放） ============
INSERT INTO case_event(case_id,district,event_type,event_time,payload) VALUES
 ('J-2026-001','JIA','INSPECTION','2026-03-05 09:00+08','{"hazard_level":"LIMITED","rectification_status":"OPEN"}'),
 ('J-2026-002','JIA','INSPECTION','2026-02-10 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('J-2026-002','JIA','REVIEW_DOWNGRADE','2026-04-10 14:00+08','{"from":"MAJOR","to":"LIMITED","reason":"复查裂缝修补达标，降级为限期整改"}'),
 ('J-2026-003','JIA','INSPECTION','2026-03-12 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('J-2026-004','JIA','INSPECTION','2026-03-15 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('J-2026-005','JIA','INSPECTION','2026-04-02 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('J-2026-006','JIA','INSPECTION','2026-04-18 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('J-2026-007','JIA','INSPECTION','2026-04-20 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('J-2026-008','JIA','INSPECTION','2026-03-22 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('J-2026-008','JIA','RECTIFY','2026-05-06 10:00+08','{"rectification_status":"RECTIFIED"}'),
 ('Y-2026-001','YI','INSPECTION','2026-03-08 09:00+08','{"hazard_level":"OBSERVE","rectification_status":"OPEN"}'),
 ('Y-2026-002','YI','INSPECTION','2025-11-10 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('Y-2026-003','YI','INSPECTION','2026-03-19 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('Y-2026-004','YI','INSPECTION','2026-04-01 09:00+08','{"hazard_level":"UNMAPPED","rectification_status":"OPEN"}'),
 ('Y-2026-005','YI','INSPECTION','2026-05-12 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('B-2026-001','BING','INSPECTION','2026-03-10 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('B-2026-002','BING','INSPECTION','2026-03-16 09:00+08','{"hazard_level":"MAJOR","rectification_status":"OPEN"}'),
 ('B-2026-003','BING','INSPECTION','2026-04-05 09:00+08','{"hazard_level":"LIMITED","rectification_status":"OPEN"}')
ON CONFLICT DO NOTHING;

-- ============ 扩展字段治理申请 ============
INSERT INTO extension_request(request_id,route,district,proposed_field_id,field_def_json,contract_json,status,evidence,catalog_version_introduced) VALUES
 ('EX-EV-001','NAMESPACE_EXT','YI','yi.ev_charge_hazard',
  '{"field_id":"yi.ev_charge_hazard","display_name":"乙区电动车充电隐患（扩展）","value_domain":{"type":"enum","values":["Y","N"]},"namespace":"YI"}',
  '{"contract_id":"MC-YI-EV","target_field_id":"std.ev_charge_hazard","values":[{"source":"Y","target":"PRESENT"},{"source":"N","target":"ABSENT"}]}',
  'DRAFT','现场照片 12 份、《高层民用建筑消防安全管理规定》第三十七条、乙区支队会商纪要',NULL),
 ('EX-EV-002','CITY_STANDARD',NULL,'std.ev_charge_hazard',
  '{"field_id":"std.ev_charge_hazard","display_name":"电动车违规充电隐患（标准）","definition":"楼内/疏散通道电动车违规充电隐患的市级标准化表达（市级统一立项，表单直接采用）","value_domain":{"type":"enum","values":["PRESENT","ABSENT"]},"namespace":"CITY"}',
  NULL,'DRAFT','市级标准化立项材料（与 EX-EV-001 同批评审）',NULL)
ON CONFLICT DO NOTHING;

-- ============ 历史查询（供复现演示） ============
INSERT INTO query_history(username,purpose,query_json,result_json,version_snapshot) VALUES
 ('city_admin','STAT',
  '{"purpose":"STAT","filters":[{"field":"std.hazard_level","op":"=","value":"MAJOR"},{"field":"std.crack_width_mm","op":">=","value":10},{"field":"std.rectification_status","op":"=","value":"OPEN"}],"groupBy":["district"],"includeTotal":true,"asOf":"2026-05-20"}',
  '{"asOf":"2026-05-20","catalogVersion":2,"disclosure":{"groups":[{"group":"JIA","count":4,"suppressed":false,"reason":null},{"group":"YI","count":null,"suppressed":true,"reason":"SMALL_GROUP"},{"group":"BING","count":null,"suppressed":true,"reason":"SMALL_GROUP"}],"total":9,"totalSuppressed":false,"rawTotal":9,"suppressedGroupCount":2,"publishedGroupCount":1,"threshold":4},"note":"2026-05-20 当时运行结果，复现时由编译器按快照重算并逐组比对"}',
  '{"catalogVersion":2,"asOf":"2026-05-20","contracts":["MC-JIA-RISK:1","MC-JIA-RECT:1","MC-YI-RISK-V1:1","MC-YI-RISK-V2:2","MC-YI-CRACK:1","MC-YI-RECT:1","MC-BING-HAZARD:1","MC-BING-CRACK:1","MC-BING-RECT:1"]}')
ON CONFLICT DO NOTHING;

INSERT INTO audit_log(username,action,detail) VALUES
 ('system','BOOTSTRAP','{"note":"种子数据加载：4 类场景、16 件表单、10 份契约"}')
ON CONFLICT DO NOTHING;

-- ============ 特征3：高风险场所排序评分规则与单调声明 ============
-- 规则总分 = 各规则分支得分之和；分级带：>=70 红 / >=50 橙 / >=30 黄 / >=10 蓝 / 其余未入级
INSERT INTO scoring_rule(rule_id,version,title,target_field,rule_type,branches_json,default_points,monotonic_direction,status) VALUES
 ('RULE_HAZARD',1,'隐患等级赋分','std.hazard_level','ENUM_BRANCH',
  '[{"op":"=","value":"MAJOR","points":40,"label":"重大隐患 +40"},
    {"op":"=","value":"LIMITED","points":20,"label":"限期整改 +20"},
    {"op":"=","value":"OBSERVE","points":8,"label":"需观察 +8"},
    {"op":"=","value":"RESCINDED","points":0,"label":"已撤销 +0"}]',
  0,'ASC','ACTIVE'),
 ('RULE_CRACK',1,'裂缝宽度分档赋分','std.crack_width_mm','NUMERIC_BRANCH',
  '[{"op":">=","value":20,"points":30,"label":"裂缝≥20mm +30"},
    {"op":">=","value":10,"points":15,"label":"10mm≤裂缝<20mm +15"}]',
  0,'ASC','ACTIVE'),
 ('RULE_RECT',1,'未整改赋分','std.rectification_status','ENUM_BRANCH',
  '[{"op":"=","value":"OPEN","points":20,"label":"未整改 +20"},
    {"op":"=","value":"RECTIFIED","points":0,"label":"已整改 +0"}]',
  0,'ASC','ACTIVE'),
 ('RULE_EV_CHARGE',1,'电动车违规充电赋分','std.ev_charge_hazard','ENUM_BRANCH',
  '[{"op":"=","value":"PRESENT","points":15,"label":"存在违规充电 +15"},
    {"op":"=","value":"ABSENT","points":0,"label":"未发现 +0"}]',
  0,'ASC','ACTIVE')
ON CONFLICT DO NOTHING;

-- 已声明安全单调关系：沿 direction 的字段序，得分不得反向（规则更新与实证扰动均据此检验）
INSERT INTO monotonic_declaration(declaration_id,field_id,direction,scope,enum_order_json,note,active) VALUES
 ('DECL_HAZARD','std.hazard_level','ASC','SCORE',
  '["RESCINDED","OBSERVE","LIMITED","MAJOR"]',
  '隐患严重度沿 撤销→观察→限期整改→重大 上升时，排序得分不得下降',TRUE),
 ('DECL_CRACK','std.crack_width_mm','ASC','SCORE',NULL,
  '裂缝宽度数值增大时得分不得下降（数值字段无需枚举序）',TRUE),
 ('DECL_RECT','std.rectification_status','ASC','SCORE',
  '["RECTIFIED","OPEN"]',
  '沿 已整改→未整改 序得分不得下降',TRUE),
 ('DECL_EV_CHARGE','std.ev_charge_hazard','ASC','SCORE',
  '["ABSENT","PRESENT"]',
  '从“未发现”到“存在”违规充电，得分不得下降',TRUE)
ON CONFLICT DO NOTHING;
