#!/usr/bin/env python3
"""端到端验证：覆盖映射/换算/事件状态/权限/披露抑制/离线合并/历史复现/扩展治理全部硬性要求。"""
import json
import sys
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080"
PASS = 0
FAIL = 0


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Auth-Token", token)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def login(u, p):
    _, r = call("POST", "/api/auth/login", body={"username": u, "password": p})
    return r["token"]


def check(name, actual, expected):
    global PASS, FAIL
    if actual == expected:
        PASS += 1
        print(f"  PASS: {name} ({actual})")
    else:
        FAIL += 1
        print(f"  FAIL: {name} 期望[{expected}] 实际[{actual}]")


def check_contains(name, payload, text):
    global PASS, FAIL
    ok = text in json.dumps(payload, ensure_ascii=False)
    if ok:
        PASS += 1
        print(f"  PASS: {name}")
    else:
        FAIL += 1
        print(f"  FAIL: {name} —— 响应未包含 [{text}]：{json.dumps(payload, ensure_ascii=False)[:300]}")


def group(result, district):
    return next(g for g in result["disclosure"]["groups"] if g["group"] == district)


CITY = login("city_admin", "city123")
JIA = login("jia_analyst", "jia123")
YI = login("yi_analyst", "yi123")
AUD = login("auditor", "audit123")

CANON = {
    "purpose": "STAT", "asOf": "2026-05-20",
    "filters": [
        {"field": "std.hazard_level", "op": "=", "value": "MAJOR"},
        {"field": "std.crack_width_mm", "op": ">=", "value": 10},
        {"field": "std.rectification_status", "op": "=", "value": "OPEN"}],
    "groupBy": ["district"], "includeTotal": True}

print("=== 场景1：标准查询 —— 映射/换算/事件状态/抑制 ===")
_, r = call("POST", "/api/query/compile", CITY, CANON)
check("JIA 计数=4（12mm限期整改件不计入重大）", group(r, "JIA")["count"], 4)
check("YI 小组抑制且不返回原始计数",
      (group(r, "YI")["suppressed"], group(r, "YI").get("count"), group(r, "YI")["reason"]),
      (True, None, "SMALL_GROUP"))
check("BING 3例敏感辖区小组抑制",
      (group(r, "BING")["suppressed"], group(r, "BING").get("count"), group(r, "BING")["reason"]),
      (True, None, "SMALL_GROUP"))
check("可发布总数=9", r["disclosure"]["total"], 9)
check("乙区代码2（Y-2026-001 需观察）未入统", "Y-2026-001" in r["includedCases"], False)
check("复查降级件 J-2026-002 未继续入重大项", "J-2026-002" in r["includedCases"], False)
check("已整改件 J-2026-008 未入统", "J-2026-008" in r["includedCases"], False)
check("乙区旧表单 Y-2026-002 按V1契约（旧义代码2=重大）入统", "Y-2026-002" in r["includedCases"], True)
_, fy1 = call("GET", "/api/forms/FI-Y-001", CITY)
check("乙区 1.2cm 经 x*10 换算为 12mm",
      any(m["transform"] == "x*10" and m["targetValue"] == "12" for m in fy1["mappingTrace"]), True)
check("编译警告：代码4不等价拒绝",
      any("yi.risk_level=4" in w for w in r["compileWarnings"]), True)
check("编译警告：待批准扩展字段拒绝",
      any("ev_charge" in w for w in r["compileWarnings"]), True)

print("=== 场景2：访问策略（辖区/案件/敏感级/目的）===")
_, rj = call("POST", "/api/query/compile", JIA, CANON)
check("甲区分析员只见 JIA 分组", sorted(g["group"] for g in rj["disclosure"]["groups"]), ["JIA"])
code, ra = call("POST", "/api/query/compile", AUD, CANON)
check_contains("审计员无 STAT 目的被拒绝", ra, "未被授予目的 STAT")
_, rs = call("POST", "/api/query/compile", JIA,
             {"purpose": "STAT", "asOf": "2026-05-20",
              "filters": [{"field": "std.hazard_level", "op": "=", "value": "MAJOR"}],
              "groupBy": ["district"], "includeTotal": True})
check("INTERNAL 密级用户拿不到 BING 敏感分组",
      any(g["group"] == "BING" for g in rs["disclosure"]["groups"]), False)
_, rc = call("POST", "/api/query/compile", YI,
             {"purpose": "CASE_INVEST", "asOf": "2026-05-20",
              "filters": [{"field": "std.hazard_level", "op": "=", "value": "MAJOR"}],
              "groupBy": ["district"], "includeTotal": True})
check("CASE_INVEST 仅见授权辖区/逐案授权（JIA逐案+YI，无BING）",
      sorted(g["group"] for g in rc["disclosure"]["groups"]), ["JIA", "YI"])

print("=== 场景3：显式不等价拒绝 ===")
code, mc = call("POST", "/api/forms/map-check", CITY,
                {"instanceId": "FI-Y-004", "fieldId": "yi.risk_level", "value": "4"})
check("map-check 返回 422", code, 422)
check_contains("乙区代码4 无法证明语义等价被拒绝", mc, "无法证明语义等价")

print("=== 场景4：历史复现（当时版本，旧表不改写）===")
_, hist = call("GET", "/api/query/history", CITY)
qid = min(h["query_id"] for h in hist)  # 种子历史查询 id=1
_, rp = call("POST", f"/api/query/history/{qid}/replay", CITY, {})
check("历史复现与存档逐组一致", rp["reproduceMatch"], True)
check("复现固定目录版本=2", rp["snapshot"]["catalogVersion"], 2)
_, fh = call("GET", "/api/forms/FI-Y-002", CITY)
check("旧表单仍固定 catalog_version=1", fh["instance"]["catalogVersion"], 1)
check("旧表单经 SUPERSEDED 的 V1 契约把代码2解释为 MAJOR",
      any(m["contractId"] == "MC-YI-RISK-V1" and m["targetValue"] == "MAJOR" for m in fh["mappingTrace"]), True)

print("=== 场景5：离线草案（IndexedDB/Worker 在前端；此处验证服务端独立复核）===")
_, s1 = call("POST", "/api/offline/sync", JIA,
             {"clientId": "e2e-ok-1", "caseId": "J-2026-E2E1", "district": "JIA",
              "templateId": "TPL-JIA-V3", "inspectDate": "2026-07-02",
              "values": {"std.crack_width_mm": "16", "jia.risk_level": "1", "jia.rectified": "false"}})
check("基线一致草案 MERGED", s1["status"], "MERGED")
_, s2 = call("POST", "/api/offline/sync", JIA,
             {"clientId": "e2e-conflict-1", "caseId": "J-2026-E2E2", "district": "JIA",
              "templateId": "TPL-JIA", "inspectDate": "2026-07-03",
              "values": {"std.smoke_density": "5", "jia.risk_level": "1"}})
check("语义/敏感级并发改动转 PENDING_CONFLICT", s2["status"], "PENDING_CONFLICT")
check("冲突含 SEMANTIC_CHANGED", any(c["type"] == "SEMANTIC_CHANGED" for c in s2["conflicts"]), True)
check("冲突含 SENSITIVITY_CHANGED", any(c["type"] == "SENSITIVITY_CHANGED" for c in s2["conflicts"]), True)
_, s3 = call("POST", "/api/offline/sync", JIA,
             {"clientId": "e2e-ok-1", "caseId": "J-2026-E2E1", "district": "JIA",
              "templateId": "TPL-JIA-V3", "inspectDate": "2026-07-02",
              "values": {"std.crack_width_mm": "16", "jia.risk_level": "1", "jia.rectified": "false"}})
check("同 clientId 重复同步幂等", s3["status"], "MERGED")
code, res = call("POST", "/api/offline/drafts/DR-e2e-conflict-1/resolve", JIA, {"decision": "REBASE_RESUBMIT"})
check("待决草案可退回重基线", res["status"], "REBASED")

print("=== 场景6：新字段治理（批准前拒绝 → 批准 → 回溯且不改写旧表单）===")
code, qb = call("POST", "/api/query/compile", CITY,
                {"purpose": "STAT", "asOf": "2026-05-20",
                 "filters": [{"field": "std.ev_charge_hazard", "op": "=", "value": "PRESENT"}],
                 "groupBy": ["district"], "includeTotal": True})
check_contains("批准前市级查询被拒绝（字段未入目录）", qb, "不存在")
_, ap = call("POST", "/api/governance/extensions/EX-EV-001/approve", CITY, {"retroactiveFrom": "2026-05-01"})
check("命名空间扩展批准并发布新目录版本", ap["status"], "APPROVED")
_, qa = call("POST", "/api/query/compile", CITY,
             {"purpose": "STAT", "asOf": "2026-05-20",
              "filters": [{"field": "std.ev_charge_hazard", "op": "=", "value": "PRESENT"}],
              "groupBy": ["district"], "includeTotal": True})
check("批准后回溯查询入统 Y-2026-005（旧表单不改写）", "Y-2026-005" in qa["includedCases"], True)
_, fh2 = call("GET", "/api/forms/FI-Y-005", CITY)
check("旧表单 FI-Y-005 仍固定 catalog_version=2", fh2["instance"]["catalogVersion"], 2)
check("FI-Y-005 扩展字段按已批准契约映射为 PRESENT",
      any(m["sourceFieldId"] == "yi.ev_charge_hazard" and m["targetValue"] == "PRESENT"
          and m["contractId"] == "MC-YI-EV" for m in fh2["mappingTrace"]), True)
_, ap2 = call("POST", "/api/governance/extensions/EX-EV-002/approve", CITY, {"retroactiveFrom": "2026-06-01"})
check("路线A市级标准立项可批准", ap2["status"], "APPROVED")
code, rr = call("POST", "/api/governance/extensions/EX-EV-001/reject", CITY, {"reason": "x"})
check_contains("已处理申请不可重复操作", rr, "已处理")

print("=== 场景7：治理演进后标准查询结果保持一致 ===")
_, r2 = call("POST", "/api/query/compile", CITY, CANON)
check("演进后 JIA 计数仍=4", group(r2, "JIA")["count"], 4)
check("演进后可发布总数仍=9", r2["disclosure"]["total"], 9)
check("演进后 YI 仍被抑制", group(r2, "YI")["suppressed"], True)

print("=== 场景8：高风险场所排序——解释图/贡献/缺失/替换/单位/单调性/稳定性 ===")
EXQ = "asOf=2026-05-20&purpose=STAT"
def venue(ranking, case_id):
    return next(v for v in ranking["ranking"] if v["caseId"] == case_id)

# 8.1 排序与评分口径（固定场所断言，不受离线合并/治理批准状态影响）
_, rk = call("GET", f"/api/explain/ranking?{EXQ}&method=PATH", CITY)
v001 = venue(rk, "J-2026-001")
check("J-2026-001 评分=55（限期20+裂缝12mm档15+未整改20）", v001["score"], 55)
check("J-2026-001 等级 ORANGE", v001["grade"], "ORANGE")
check("排序结果带规则版本清单", all(x.startswith("RULE_") for x in rk["ruleVersions"]) and len(rk["ruleVersions"]) == 4, True)
_, rk_sh = call("GET", f"/api/explain/ranking?{EXQ}&method=SHAPLEY", CITY)
sh = venue(rk_sh, "J-2026-001")["contributions"]
check("Shapley 三玩家8次联盟评估", sh["coalitionEvaluations"], 8)
check("Shapley 分级效用效率成立", sh["efficiencyCheck"]["pass"], True)
check("Shapley 分数尺度与路径归因一致", sh["efficiencyCheck"]["scoreScaleMatchesPath"], True)

# 8.2 规则解释图
_, g001 = call("GET", f"/api/explain/venues/J-2026-001/graph?{EXQ}", CITY)
check("解释图总分55", g001["score"], 55)
node_types = {n["type"] for n in g001["nodes"]}
check("解释图含 SOURCE/STD/RULE/SCORE 节点", {"SOURCE", "STD", "RULE", "SCORE"} <= node_types, True)
src_node = next(n for n in g001["nodes"] if n["type"] == "SOURCE")
check("源节点携带提交时版本与现行状态", "fieldDefVersionAtSubmit" in src_node and "fieldStatusNow" in src_node, True)

# 8.3 缺失情景：Y-2026-004 等级语义未决（事件UNMAPPED+代码4拒绝），有批准路径→区间且可翻转
_, ms = call("GET", f"/api/explain/venues/Y-2026-004/scenarios/missing?field=std.hazard_level&{EXQ}", CITY)
check("缺失情景给出分数区间且上限>下限", ms["scoreInterval"]["max"] > ms["scoreInterval"]["min"], True)
check("缺失补全可使名次翻转→只给 best/worst 区间", ms["rankFlipPossible"] is True and "rankInterval" in ms, True)
check("不可达取值不产生分数", all(s["score"] is None for s in ms["scenarios"] if s["status"] == "UNREACHABLE"), True)

# 8.4 替换情景：废止字段守卫 + 等价拒绝 + 基线行
_, ref = call("GET", f"/api/explain/venues/Y-2026-002/scenarios/substitution?field=yi.risk_level&useCurrentMeaning=true&{EXQ}", CITY)
check("废止字段按现行含义回算历史→REFUSED", ref["guard"]["refusal"], "REFUSED")
_, sub = call("GET", f"/api/explain/venues/Y-2026-001/scenarios/substitution?field=yi.risk_level&useCurrentMeaning=false&{EXQ}", CITY)
check("代码4无市级等价→REJECTED", any(s["status"] == "REJECTED" for s in sub["scenarios"]), True)
check("当前取值保留 BASELINE 行", any(s["status"] == "BASELINE" for s in sub["scenarios"]), True)

# 8.5 单位换算：批准换算可计算，无批准换算 BLOCKED
_, us = call("GET", f"/api/explain/venues/Y-2026-001/scenarios/unit?field=yi.crack_width_cm&{EXQ}", CITY)
comp = [s for s in us["scenarios"] if s["status"] == "COMPUTED"]
blk = [s for s in us["scenarios"] if s["status"] == "BLOCKED"]
check("1.2cm 经批准 x*10 换算为 12mm", len(comp) == 1 and comp[0]["extra"]["convertedValue"] == "12", True)
check("无批准 x*1 直取 BLOCKED 且不进贡献", len(blk) == 1 and blk[0]["extra"]["blockedFromContribution"] is True, True)
_, us2 = call("GET", f"/api/explain/venues/J-2026-001/scenarios/unit?field=std.crack_width_mm&{EXQ}", CITY)
check("市级标准字段恒等换算 COMPUTED", us2["scenarios"][0]["status"], "COMPUTED")

# 8.6 稳定性报告
_, st = call("GET", f"/api/explain/venues/Y-2026-001/stability?{EXQ}", CITY)
check("稳定性报告基线分43", st["baseline"]["score"], 43)
check("报告含三类情景与阻塞/拒绝统计",
      {"missingScenarios", "substitutionScenarios", "unitScenarios"} <= set(st.keys())
      and "blockedConversions" in st["stability"], True)

# 8.7 单调性检验：扫描/更新候选/漂移
_, sw = call("GET", "/api/explain/monotonicity/rules", CITY)
check("现行规则全部通过单调扫描", sw["pass"] is True and sw["violationCount"] == 0, True)
bad_rule = {"ruleId": "RULE_CRACK", "candidateVersion": 2, "targetField": "std.crack_width_mm",
            "ruleType": "NUMERIC_BRANCH",
            "branches": [{"op": ">=", "value": 20, "points": 10, "label": "反向"},
                         {"op": ">=", "value": 10, "points": 30, "label": "反向"}],
            "defaultPoints": 0, "monotonicDirection": "ASC"}
_, ru_bad = call("POST", "/api/explain/monotonicity/rule-update", CITY, bad_rule)
check("违反单调声明的候选规则被拒", ru_bad["decision"], "REJECTED_MONOTONIC_BREACH")
good_rule = dict(bad_rule, branches=[{"op": ">=", "value": 20, "points": 30, "label": "高"},
                                     {"op": ">=", "value": 10, "points": 15, "label": "中"}])
_, ru_good = call("POST", "/api/explain/monotonicity/rule-update", CITY, good_rule)
check("合规候选规则可接受", ru_good["decision"], "ACCEPTABLE")
_, drift = call("GET", f"/api/explain/monotonicity/drift?{EXQ}", CITY)
check("实证扰动无单调违反", drift["pass"] is True, True)
check("漂移清单识别 yi.risk_level 同名不同义",
      any(d["sourceField"] == "yi.risk_level" for d in drift["driftedFieldUses"]), True)

# 8.8 访问策略同口径
_, rj = call("GET", f"/api/explain/ranking?{EXQ}", JIA)
check("甲区分析员只见 JIA 场所", all(v["district"] == "JIA" for v in rj["ranking"]) and len(rj["ranking"]) > 0, True)
code, _ = call("GET", "/api/explain/ranking?asOf=2026-05-20&purpose=STAT", AUD)
check("审计员无 STAT 目的被拒(422)", code, 422)

print(f"\n============ 汇总: PASS={PASS} FAIL={FAIL} ============")
sys.exit(1 if FAIL else 0)
