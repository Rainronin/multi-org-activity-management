"""生成系统功能图和传统 Chen 概念 ER 图；只使用 Python 标准库。"""
from pathlib import Path
import html
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "图示成果"
TITLE = "基于 Vue 的多组织活动管理系统设计与实现"

# 属性是概念属性，不指定字段类型、数据库产品或物理表结构。
ENTITIES = {
    "account": ("账号", ["账号编号", "登录名", "认证版本", "账号状态"]),
    "organization": ("组织", ["组织编号", "组织名称", "组织说明", "组织状态"]),
    "membership": ("组织成员", ["成员编号", "组织角色", "成员状态", "加入时间"]),
    "activity": ("活动", ["活动编号", "活动名称", "活动状态", "预算额度"]),
    "task": ("任务", ["任务编号", "任务标题", "截止时间", "任务状态"]),
    "registration": ("报名", ["报名编号", "报名状态", "申请时间", "处理原因"]),
    "expense": ("支出记录", ["支出编号", "用途", "金额", "确认状态"]),
    "material": ("物资项", ["物资编号", "物资名称", "需求数量", "准备状态"]),
    "execution": ("执行记录", ["记录编号", "记录类型", "记录内容", "记录时间"]),
    "feedback": ("反馈", ["反馈编号", "总体评分", "文字建议", "提交时间"]),
    "document": ("活动文稿", ["文稿标识", "文稿类型", "正文内容", "确认状态"]),
    "generation": ("AI 生成记录", ["生成编号", "生成类型", "输入来源", "生成结果"]),
    "attachment": ("附件", ["附件编号", "原始名称", "存储标识", "业务用途"]),
    "invitation": ("组织邀请", ["邀请编号", "到期时间", "使用上限", "邀请状态"]),
    "notification": ("站内提示", ["提示编号", "提示摘要", "已读状态", "创建时间"]),
    "audit": ("操作记录", ["操作编号", "操作类型", "变更内容", "发生时间"]),
}

RELATIONS = [
    ("account", "拥有", "membership", "1", "N", "一个账号可有多个组织成员身份；每个身份属于一个账号。"),
    ("organization", "包含", "membership", "1", "N", "每个成员身份只属于一个组织；账号与组织的组合唯一。"),
    ("organization", "举办", "activity", "1", "N", "每场活动只属于一个组织，不设计跨组织联合主办。"),
    ("membership", "负责", "activity", "1", "N", "每场活动有一位当前负责人；负责人须为同组织有效成员。"),
    ("activity", "分解为", "task", "1", "N", "活动可包含多个任务，每个任务属于一场活动。"),
    ("membership", "承担", "task", "1", "N", "每个任务有一位当前负责人，须为同组织有效成员。"),
    ("activity", "接收", "registration", "1", "N", "每条报名只针对一场活动。"),
    ("account", "申请", "registration", "1", "N", "账号与活动的报名组合唯一；不要求账号加入该组织。"),
    ("activity", "收集", "feedback", "1", "N", "反馈属于一场活动；活动与账号组合唯一。"),
    ("account", "提交", "feedback", "1", "N", "反馈直接关联账号，支持有资格的外部报名者。"),
    ("activity", "记录费用", "expense", "1", "N", "预算作为活动属性；确认的支出才计入支出汇总。"),
    ("membership", "登记", "expense", "1", "N", "登记者是本组织具有费用登记权限的成员。"),
    ("membership", "确认费用", "expense", "0..1", "N", "每笔费用至多一位当前确认者；确认者具有组织负责人权限，历史动作留痕。"),
    ("activity", "准备", "material", "1", "N", "只表示活动准备清单，不建立库存或借还关系。"),
    ("membership", "负责准备", "material", "0..1", "N", "物资可暂未指派；指派时须为同组织有效成员。正常归档时未就绪、未取消的物资须有有效责任人及处置说明。"),
    ("activity", "留存执行", "execution", "1", "N", "执行记录说明过程和异常，不记录考勤。"),
    ("membership", "填写", "execution", "1", "N", "由有活动管理权限的成员填写。"),
    ("activity", "形成", "document", "1", "N", "文稿以活动及类型标识，包含方案、总结与取消说明。当前正文存于活动；确认历史独立追加，不建立独立文稿编号。"),
    ("account", "确认文稿", "document", "0..1", "N", "未确认正文可无确认人；确认快照记录确认账号。编写及修改者通过操作记录追溯，不要求单独文稿作者字段。"),
    ("organization", "保留生成", "generation", "1", "N", "每次模型调用属于一个组织，共用该组织当前配置；连接测试不关联活动。"),
    ("activity", "触发", "generation", "0..1", "N", "方案和总结生成关联一场活动；连接测试没有活动。生成记录与业务正文独立，采用标记不代表已保存正文。"),
    ("account", "发起生成", "generation", "1", "N", "方案和总结发起者须有目标活动生成权限；连接测试发起者须为组织负责人。"),
    ("activity", "归集", "attachment", "1", "N", "每个附件必须属于一场活动，再确定具体业务用途。"),
    ("account", "上传", "attachment", "1", "N", "上传者必须有目标活动及业务对象权限。"),
    ("expense", "附凭证", "attachment", "0..1", "N", "附件可关联一笔支出；若用途为票据，该关联必填。"),
    ("task", "附资料", "attachment", "0..1", "N", "任务资料关联一个任务；任务资料、费用凭证及活动资料用途互斥。活动资料不绑定单独文稿对象。"),
    ("organization", "发出", "invitation", "1", "N", "邀请只能用于加入一个组织，默认授予普通成员身份。"),
    ("account", "创建邀请", "invitation", "1", "N", "创建时需要对应组织的负责人权限。"),
    ("account", "接收提示", "notification", "1", "N", "每条提示属于一个接收账号，多人通知分别保存已读状态。"),
    ("activity", "产生提示", "notification", "1", "N", "首期提示围绕活动及其任务、报名、审批变更产生。"),
    ("organization", "保留", "audit", "0..1", "N", "组织业务操作保留组织范围；全局账号操作不属于组织，范围在操作记录中表达。"),
    ("account", "执行操作", "audit", "0..1", "N", "记录可确定的操作账号；未登录操作可为空，不伪造历史身份，成员退出后历史署名保留。"),
    ("activity", "关联留痕", "audit", "0..1", "N", "活动相关操作关联活动；组织成员维护等操作可以没有活动。"),
]


def esc(value):
    return html.escape(str(value), quote=True)


class Diagram:
    def __init__(self, title, width, height):
        self.title, self.width, self.height = title, width, height
        self.edges, self.nodes, self.positions, self.keys = [], [], {}, set()

    def text(self, x, y, value, size=23, key=False, cls=""):
        return f'<text x="{x}" y="{y}" class="{cls}" font-size="{size}"' + (' text-decoration="underline"' if key else '') + f'>{esc(value)}</text>'

    def entity(self, key, x, y, attrs="corners"):
        assert key in ENTITIES and key not in self.keys
        self.keys.add(key)
        self.positions[key] = (x, y)
        name, properties = ENTITIES[key]
        self.nodes.append(f'<g data-entity="{key}"><rect x="{x-100}" y="{y-36}" width="200" height="72"/>')
        self.nodes.append(self.text(x, y+2, name, 25, cls="entity-label") + '</g>')
        if attrs == "bottom":
            points = [(x-105, y+120), (x+105, y+120), (x, y+215)]
            properties = properties[:3]
        else:
            points = [(x-150, y-125), (x+150, y-125), (x-150, y+125), (x+150, y+125)]
        for i, ((ax, ay), prop) in enumerate(zip(points, properties)):
            dx, dy = ax-x, ay-y
            t1 = min(100 / abs(dx) if dx else 10, 36 / abs(dy) if dy else 10)
            t2 = 1 / ((dx/92)**2 + (dy/33)**2)**0.5
            self.edges.append(f'<path class="attribute-line" d="M{x+dx*t1:.2f},{y+dy*t1:.2f} L{ax-dx*t2:.2f},{ay-dy*t2:.2f}"/>')
            self.nodes.append(f'<g data-attribute="{key}.{i}"><ellipse cx="{ax}" cy="{ay}" rx="92" ry="33"/>')
            self.nodes.append(self.text(ax, ay+1, prop, 21, key=i == 0) + '</g>')

    def relation(self, source, target, label, points, diamond):
        assert source in self.keys and target in self.keys
        cards = next((r[3:5] for r in RELATIONS if r[:3] == (source, label, target)), None)
        assert cards is not None, f"图中联系未登记：{source}:{label}:{target}"
        assert tuple(points[0]) != tuple(points[-1])
        path = "M" + " L".join(f"{x},{y}" for x,y in points)
        self.edges.append(f'<path class="relationship-line" d="{path}"/>')
        x,y = diamond
        self.nodes.append(f'<g data-relation="{source}:{label}:{target}"><polygon points="{x},{y-47} {x+85},{y} {x},{y+47} {x-85},{y}"/>')
        self.nodes.append(self.text(x, y+1, label, 21) + '</g>')
        for idx, card in [(0,cards[0]),(-1,cards[1])]:
            p,q = points[idx], points[1 if idx == 0 else -2]
            dx,dy = q[0]-p[0],q[1]-p[1]
            length=(dx*dx+dy*dy)**0.5
            assert length > 0
            # 将基数放在线段侧边，避免覆盖连接线。
            tx=p[0]+dx/length*32 + (22 if abs(dy)>abs(dx) else 0)
            ty=p[1]+dy/length*32 - (18 if abs(dx)>=abs(dy) else 0)
            self.nodes.append(self.text(round(tx,1),round(ty,1),card,22,cls="cardinality"))

    def svg(self):
        svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{self.width}" height="{self.height}" viewBox="0 0 {self.width} {self.height}" role="img" aria-labelledby="svg-title">
<title id="svg-title">{esc(TITLE)} — {esc(self.title)} — 传统 Chen ER 图</title>
<desc>矩形为实体，椭圆为主要属性，下划线为实体标识，菱形为联系，1 和 N 表示最大基数。完整关系及条件见页面说明。</desc>
<style>text{{font-family:'Noto Serif CJK SC','SimSun','Songti SC',serif;fill:#17252d;text-anchor:middle;dominant-baseline:middle}}rect,ellipse,polygon{{fill:white;stroke:#263a45;stroke-width:2}}path{{fill:none;stroke:#657781;stroke-width:1.7}}.relationship-line{{stroke:#334c59;stroke-width:2}}.entity-label{{font-weight:700}}.cardinality{{font-family:'Cambria','Times New Roman',serif;paint-order:stroke;stroke:white;stroke-width:8;stroke-linejoin:round}}.attribute-line{{stroke:#85939b;stroke-width:1.3}}</style>
<rect width="100%" height="100%" style="fill:white;stroke:none"/>
{''.join(self.edges)}{''.join(self.nodes)}</svg>'''
        ET.fromstring(svg)
        return svg


def overview():
    d=Diagram("总体主干：组织与活动",2500,2040)
    for key,x,y in [("account",250,230),("membership",950,230),("organization",1650,230),("activity",1650,730)]:
        d.entity(key,x,y)
    for key,x in zip(["task","registration","expense","material","execution","feedback"],[250,650,1050,1450,1850,2250]):
        d.entity(key,x,1280,"bottom")
    d.entity("document",750,1770)
    d.entity("generation",1750,1770)
    d.relation("account","membership","拥有",[(350,230),(850,230)],(600,230))
    d.relation("organization","membership","包含",[(1550,230),(1050,230)],(1300,230))
    d.relation("organization","activity","举办",[(1650,266),(1650,694)],(1650,480))
    for key,x,label in zip(["task","registration","expense","material","execution","feedback"],[250,650,1050,1450,1850,2250],["分解为","接收","记录费用","准备","留存执行","收集"]):
        d.relation("activity",key,label,[(1650,766),(1650,1000),(x,1000),(x,1244)],(x,1110))
    d.relation("activity","document","形成",[(1550,730),(60,730),(60,1560),(750,1560),(750,1734)],(750,1560))
    d.relation("activity","generation","触发",[(1750,730),(2440,730),(2440,1560),(1750,1560),(1750,1734)],(1750,1560))
    return d


def identity():
    d=Diagram("组织与活动：身份、归属和负责人",2100,1160)
    for k,x,y in [("account",300,240),("membership",1050,240),("organization",1800,240),("activity",1050,900),("invitation",1800,900)]:d.entity(k,x,y)
    d.relation("account","membership","拥有",[(400,240),(950,240)],(675,240))
    d.relation("organization","membership","包含",[(1700,240),(1150,240)],(1425,240))
    d.relation("membership","activity","负责",[(1050,276),(1050,864)],(1050,575))
    d.relation("organization","invitation","发出",[(1800,276),(1800,864)],(1800,575))
    d.relation("organization","activity","举办",[(1800,276),(1800,450),(1450,450),(1450,900),(1150,900)],(1450,630))
    return d


def participation():
    d=Diagram("任务、报名与反馈",2100,1270)
    for k,x,y in [("membership",300,230),("task",1050,230),("activity",300,650),("registration",1050,650),("account",1800,650),("feedback",1050,1070)]:d.entity(k,x,y)
    d.relation("membership","task","承担",[(400,230),(950,230)],(675,230))
    d.relation("activity","task","分解为",[(400,650),(610,650),(610,455),(1050,455),(1050,266)],(1050,420))
    d.relation("activity","registration","接收",[(400,650),(950,650)],(675,650))
    d.relation("account","registration","申请",[(1700,650),(1150,650)],(1425,650))
    d.relation("activity","feedback","收集",[(300,686),(300,1070),(950,1070)],(650,1070))
    d.relation("account","feedback","提交",[(1800,686),(1800,1070),(1150,1070)],(1450,1070))
    return d


def operations():
    d=Diagram("经费、物资与执行记录",2100,1200)
    for k,x,y in [("membership",300,240),("activity",1050,240),("expense",300,910),("material",1050,910),("execution",1800,910)]:d.entity(k,x,y)
    d.relation("membership","expense","登记",[(300,276),(300,874)],(300,570))
    d.relation("activity","expense","记录费用",[(950,240),(655,240),(655,910),(400,910)],(655,570))
    d.relation("activity","material","准备",[(1050,276),(1050,874)],(1050,570))
    d.relation("activity","execution","留存执行",[(1150,240),(1800,240),(1800,874)],(1800,570))
    return d


def content():
    d=Diagram("文稿、AI 生成与附件",2100,1240)
    for k,x,y in [("activity",300,240),("document",1050,240),("generation",1800,240),("expense",300,930),("attachment",1050,930),("account",1800,930)]:d.entity(k,x,y)
    d.relation("activity","document","形成",[(400,240),(950,240)],(675,240))
    d.relation("account","generation","发起生成",[(1800,894),(1800,276)],(1800,680))
    d.relation("activity","generation","触发",[(200,240),(30,240),(30,490),(1800,490),(1800,276)],(1050,490))
    d.relation("activity","attachment","归集",[(300,276),(300,585),(1050,585),(1050,894)],(1050,735))
    d.relation("expense","attachment","附凭证",[(400,930),(950,930)],(675,930))
    d.relation("account","attachment","上传",[(1700,930),(1150,930)],(1425,930))
    return d


def governance():
    d=Diagram("邀请、站内提示与操作记录",2100,1220)
    for k,x,y in [("organization",300,240),("account",1050,240),("activity",1800,240),("invitation",300,920),("audit",1050,920),("notification",1800,920)]:d.entity(k,x,y)
    d.relation("organization","invitation","发出",[(300,276),(300,884)],(300,565))
    d.relation("account","invitation","创建邀请",[(950,240),(650,240),(650,920),(400,920)],(650,565))
    d.relation("account","audit","执行操作",[(1050,276),(1050,884)],(1050,565))
    d.relation("account","notification","接收提示",[(1150,240),(1425,240),(1425,920),(1700,920)],(1425,565))
    d.relation("activity","notification","产生提示",[(1800,276),(1800,884)],(1800,565))
    # 组织留痕及可选活动归属在下方完整联系表说明，避免同图交叉线掩盖基数。
    return d


FUNCTION_GROUPS = [
    ("组织与协作", [
        ("M01", "账号、组织与成员", ["注册、登录与账号恢复", "组织创建、切换与隔离", "成员邀请、接受与退出", "角色授权与负责人移交"]),
        ("M03", "任务协作", ["任务分派与负责人", "截止时间与优先级", "状态推进与逾期提示", "任务说明及附件"]),
        ("M10", "必要站内提示", ["任务分派提醒", "报名与审批结果", "活动变更与取消提示", "站内查看与业务跳转"]),
    ]),
    ("活动运营", [
        ("M02", "活动管理", ["方案及基础信息", "立项确认、驳回与修改", "筹备、执行与异常记录", "取消、归档与更正"]),
        ("M04", "活动报名", ["内部及公开活动报名", "名额审核与重复校验", "报名取消与状态查看", "名单筛选与导出"]),
        ("M06", "物资准备", ["物资名称、数量与单位", "可选准备负责人", "准备状态与备注", "活动物资清单查看"]),
    ]),
    ("资源与复盘", [
        ("M05", "经费记录", ["预算与支出登记", "凭证上传及无票说明", "确认、驳回与作废", "支出汇总与预算余额"]),
        ("M07", "反馈与复盘", ["评分及文字反馈", "问题记录与改进项", "总结编辑与人工确认", "反馈及总结查看"]),
        ("M08", "资料归档", ["活动材料归集", "按名称查找与受控下载", "归档只读", "重新打开与更正留痕"]),
    ]),
    ("数据与智能辅助", [
        ("M09", "基础工作台", ["本人待办与活动概览", "任务完成与逾期统计", "报名人数与名额使用率", "活动费用与预算统计"]),
        ("M11", "AI 活动总结", ["选择活动授权数据", "生成活动总结草稿", "编辑、确认与来源核对", "失败后继续人工编写"]),
        ("M12", "AI 方案生成", ["输入目标与资源约束", "生成活动方案草稿", "编辑确认并保存方案", "失败后继续人工编写"]),
    ]),
]


def function_svg():
    """用共享分支表示功能包含关系，不表示业务流转顺序。"""
    edges, nodes = [], []

    def line(points):
        edges.append('<path d="M' + ' L'.join(f'{x},{y}' for x,y in points) + '"/>')

    def box(x, y, width, height, label, kind, module=None):
        marker = f' data-module="{module}"' if module else ''
        nodes.append(f'<g class="{kind}"{marker}><rect x="{x-width/2}" y="{y-height/2}" width="{width}" height="{height}"/><text x="{x}" y="{y+1}">{esc(label)}</text></g>')

    box(1080,130,1050,68,"多组织活动管理系统","root")
    line([(1080,164),(1080,207)])
    line([(270,207),(1890,207)])
    for i,(group,modules) in enumerate(FUNCTION_GROUPS):
        cx=270+i*540
        box(cx,265,450,64,group,"group")
        line([(cx,207),(cx,233)])
        spine=cx-208
        line([(cx,297),(cx,325),(spine,325),(spine,948)])
        for j,(mid,name,features) in enumerate(modules):
            my=368+j*290
            box(cx+20,my,380,54,f'{mid}  {name}',"module ai" if mid in {"M11","M12"} else "module",mid)
            line([(spine,my),(cx-170,my)])
            leaf_spine=cx-148
            line([(leaf_spine,my+27),(leaf_spine,my+227)])
            for k,feature in enumerate(features):
                fy=my+77+k*50
                box(cx+51,fy,305,38,feature,"feature")
                line([(leaf_spine,fy),(cx-101.5,fy)])
    result=f'''<svg xmlns="http://www.w3.org/2000/svg" width="2160" height="1280" viewBox="0 0 2160 1280" role="img" aria-labelledby="function-title">
<title id="function-title">{esc(TITLE)} — 系统功能结构图</title>
<desc>按系统、四个功能分组、十二个模块和子功能分层，模块编号对应项目计划书。连线表示包含关系，不表示流程顺序。</desc>
<style>text{{font-family:'SimSun','Songti SC',serif;fill:#20333d;text-anchor:middle;dominant-baseline:middle;font-size:24px}}rect{{fill:white;stroke:#71848e;stroke-width:1.5}}path{{fill:none;stroke:#6e8794;stroke-width:1.7}}.root rect{{fill:#eaf2f7;stroke:#285874;stroke-width:2}}.root text{{font-size:32px;font-weight:bold}}.group rect{{fill:#f3f6f8;stroke:#405d6e;stroke-width:2}}.group text{{font-size:28px;font-weight:bold}}.module text{{font-size:25px;font-weight:bold}}.ai rect{{fill:#edf5fa;stroke:#377492}}.feature text{{font-size:23px}}</style>
<rect width="2160" height="1280" style="fill:white;stroke:none"/>
<text x="1080" y="43" style="font-size:30px;font-weight:bold">{esc(TITLE)}</text>
{''.join(edges)}{''.join(nodes)}</svg>'''
    root=ET.fromstring(result)
    ids=[g.attrib['data-module'] for g in root.iter() if 'data-module' in g.attrib]
    assert len(ids)==12 and set(ids)=={f'M{i:02}' for i in range(1,13)}
    # 从当前计划书读取模块，避免绘图时悄然新增或漏掉模块。
    plan=(ROOT / '项目文档' / '项目计划书.md').read_text(encoding='utf-8')
    assert all(f'| {mid} | {name} |' in plan for _,modules in FUNCTION_GROUPS for mid,name,_ in modules)
    return result


def build():
    OUT.mkdir(exist_ok=True)
    diagrams=[overview(),identity(),participation(),operations(),content(),governance()]
    notes=[
        "主干展示活动及核心业务。附件、邀请、站内提示和操作记录见相应局部图；负责人、操作人等补充联系见完整联系表。共线分支仅表示共享同一端点。",
        "组织角色属于组织成员身份，不属于全局账号。同一账号可在多个组织具有不同角色；活动负责人必须是同组织有效成员。",
        "报名和反馈直接关联账号与活动，不强制依赖组织成员身份。活动＋账号分别在报名、反馈中唯一；反馈资格按计划书校验。",
        "预算是活动的属性；预算余额为计算结果，不建资金账户。物资仅是活动清单。登记、确认、负责准备与填写等人员联系见完整联系表。",
        "活动文稿合并表示方案、总结及取消说明，当前正文与确认历史由活动模块维护。AI 采用状态独立记录，不建立最终文稿的生成来源外键；连接测试不关联活动。附件仅分活动资料、任务资料及费用凭证。",
        "每条站内提示只对应一个接收账号。组织业务操作保留组织范围及可选活动；全局账号操作可无组织，未登录操作可无账号。其余联系见完整联系表。",
    ]
    svg_strings=[d.svg() for d in diagrams]
    # 最小自检：实体覆盖、所有联系端点有效、标识属性和 SVG 结构完整。
    assert set().union(*(d.keys for d in diagrams)) == set(ENTITIES)
    assert all(a in ENTITIES and b in ENTITIES for a,_,b,_,_,_ in RELATIONS)
    for d,s in zip(diagrams,svg_strings):
        root=ET.fromstring(s)
        assert len(root.findall('.//{http://www.w3.org/2000/svg}rect')) == len(d.keys)+1
        assert s.count('text-decoration="underline"') == len(d.keys)
    OUT.joinpath("项目ER图.svg").write_text(svg_strings[0],encoding="utf-8")
    functions=function_svg()
    OUT.joinpath("系统功能图.svg").write_text(functions,encoding="utf-8")
    svg_strings.append(functions)
    notes.append("按功能包含关系组织为四组，完整覆盖计划书 M01—M12；分组仅用于展示，不新增模块。AI 总结与方案生成只提供可编辑草稿，由用户确认。")
    options='<option value="6" selected>系统功能结构图</option><optgroup label="传统 Chen ER 图">'+"".join(f'<option value="{i}">{esc(d.title)}</option>' for i,d in enumerate(diagrams))+'</optgroup>'
    rows="".join(f'<tr><td>{esc(ENTITIES[a][0])}</td><td>{esc(label)}</td><td>{esc(ENTITIES[b][0])}</td><td>{ca} : {cb}</td><td>{esc(note)}</td></tr>' for a,label,b,ca,cb,note in RELATIONS)
    svg_json=json.dumps(svg_strings,ensure_ascii=False).replace("</","<\\/")
    notes_json=json.dumps(notes,ensure_ascii=False)
    sizes_json=json.dumps([[d.width,d.height] for d in diagrams]+[[2160,1280]])
    page='''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>__TITLE__ · 系统功能与 ER 图</title><style>
:root{color-scheme:light;--ink:#20333d;--muted:#5d707b;--line:#ced8dd;--accent:#205c7a;--page:#edf2f5}
*{box-sizing:border-box}body{margin:0;color:var(--ink);background:var(--page);font-family:'Segoe UI','Microsoft YaHei',sans-serif}header,main{max-width:1600px;margin:auto;padding:26px 32px}header{padding-bottom:15px}h1{font-family:'SimSun','Songti SC',serif;font-size:26px;margin:6px 0 10px;line-height:1.5}p{margin:8px 0;line-height:1.7}.caption{font-size:13px;color:var(--muted)}.legend{display:flex;gap:22px;flex-wrap:wrap;font-size:14px;margin-top:18px}.legend span{display:inline-flex;align-items:center;gap:9px}.shape{display:inline-block;width:27px;height:18px;border:1.5px solid var(--ink);background:white}.oval{border-radius:50%}.diamond{width:17px;height:17px;transform:rotate(45deg);margin:0 5px}main{padding-top:4px}.tools{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:6px 0 14px}label{font-size:14px}select,button{font:inherit;color:var(--ink);background:white;border:1px solid #b5c5ce;border-radius:5px;padding:9px 12px}select{max-width:100%}button{cursor:pointer}button:hover{border-color:var(--accent);background:#f4f8fa}button:focus-visible,select:focus-visible,input:focus-visible,a:focus-visible{outline:3px solid #357fab;outline-offset:3px}input{accent-color:var(--accent);max-width:130px}.scale{display:flex;align-items:center;gap:8px}output{font-size:13px;min-width:45px;font-variant-numeric:tabular-nums}.frame{height:690px;overflow:auto;background:white;border:1px solid var(--line);border-radius:5px;overscroll-behavior:contain}.stage{margin:auto;line-height:0}.stage svg{display:block;width:100%;height:100%}.view-note{font-size:14px;color:#425c69;min-height:48px}.heading-row{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}h2{font-size:17px;font-weight:600;margin:22px 0 12px}details{background:white;border:1px solid var(--line);padding:15px 18px;margin-top:16px;border-radius:5px}summary{cursor:pointer;font-weight:600}.table-scroll{overflow:auto}table{width:100%;border-collapse:collapse;min-width:740px;font-size:14px;margin-top:15px}th,td{text-align:left;border-bottom:1px solid #e3e9ed;padding:12px 10px;line-height:1.6}th{background:#f3f6f8;font-weight:600}td:nth-child(-n+4){white-space:nowrap}ul{padding-left:22px;line-height:1.85;font-size:14px}a{color:var(--accent)}footer{font-size:13px;color:var(--muted);margin:24px 0} @media(max-width:640px){header,main{padding:18px 14px}h1{font-size:21px}.tools{gap:8px}.frame{height:570px}.scale{flex-basis:100%}.legend{gap:14px;font-size:12px}}@media print{.tools,details,footer,.caption{display:none}.frame{height:auto;overflow:visible;border:0}.stage{width:100%!important;height:auto!important}header,main{padding:0}body{background:white}}
[hidden]{display:none!important}
</style></head><body>
<header><p class="caption">系统设计图示 · 根据当前项目计划书绘制</p><h1>__TITLE__</h1>
<p id="diagram-heading">系统功能结构图</p><div id="function-legend" class="legend"><span>系统 → 功能分组 → 模块 → 子功能</span><span>M01—M12 对应计划书</span></div><div class="legend" data-er-only hidden aria-label="ER 图例"><span><i class="shape" aria-hidden="true"></i>实体</span><span><i class="shape oval" aria-hidden="true"></i>主要属性</span><span><i class="shape diamond" aria-hidden="true"></i>联系</span><span><u>编号</u>实体标识</span><span>1 : N　一对多</span><span>0..1　可选单个</span></div></header>
<main><div class="tools"><label for="view">查看</label><select id="view">__OPTIONS__</select><div class="scale"><label for="zoom">缩放</label><input id="zoom" type="range" min="20" max="160" value="60" step="5"><output id="zoom-value" for="zoom">60%</output></div><button id="fit" type="button">适合宽度</button><button id="export" type="button">保存当前 SVG</button></div>
<div class="frame" id="frame" aria-label="图示画布，可滚动查看"><div id="stage" class="stage">__INITIAL__</div></div>
<p class="view-note" id="view-note" aria-live="polite">__NOTE__</p>
<details data-er-only hidden><summary>读图约定与建模边界</summary><ul>
<li>这是概念 ER 图，不是物理建表稿。图中展示主要属性，未穷举账号凭据、所有时间字段、审批人及关联字段；技术基线见架构文档，物理字段及约束在数据库详细设计中落实。</li>
<li>1、N 表示联系两端的最大基数；N 端允许零条记录。0..1 显式表示可选单个关联，具体必填条件见联系表。跨图同名实体是同一实体。</li>
<li>组织角色是成员身份的属性，活动负责人通过“负责”联系表示；外部报名者通过账号报名，不需要先成为组织成员。</li>
<li>同一账号与组织的成员关系唯一；同一活动与账号的报名、反馈分别唯一。跨组织关联必须被拒绝。</li>
<li>活动文稿以活动及类型标识，概念上包含方案、总结与取消说明。当前方案、总结／取消说明保存在活动字段；确认时向确认历史追加正文、来源与确认人快照，不建设独立文稿表或通用版本库。</li>
<li>AI 生成记录保留输入范围、结果及采用人／时间。采用草稿与保存业务正文是两个动作，不把采用标记解释为最终正文的精确生成来源；连接测试属于组织且不关联活动。</li>
<li>附件必属活动，用途为活动资料、任务资料或费用凭证；仅任务资料关联任务、费用凭证关联支出，两者互斥。方案、总结相关文件作为活动资料管理，没有单独文稿附件关系。</li>
<li>归档主要表现为活动状态和操作记录；预算余额、完成率等为计算结果。签到、定位、OCR、库存与支付不在模型中。</li>
</ul></details>
<details data-er-only hidden><summary>完整联系与约束（__COUNT__ 项）</summary><div class="table-scroll"><table><thead><tr><th>实体 A</th><th>联系</th><th>实体 B</th><th>A : B</th><th>约束说明</th></tr></thead><tbody>__ROWS__</tbody></table></div></details>
<footer>功能图展示系统做什么，ER 图展示数据如何关联。当前依据：<a href="../项目文档/项目计划书.md">项目计划书</a>。</footer></main>
<script>
const diagrams=__DIAGRAMS__,notes=__NOTES__,sizes=__SIZES__;
const view=document.getElementById('view'),zoom=document.getElementById('zoom'),stage=document.getElementById('stage'),frame=document.getElementById('frame');
function scale(){const [w,h]=sizes[Number(view.value)];const ratio=Number(zoom.value)/100;stage.style.width=`${w*ratio}px`;stage.style.height=`${h*ratio}px`;document.getElementById('zoom-value').value=zoom.value+'%';}
function fit(){zoom.value=Math.max(20,Math.min(160,Math.floor((frame.clientWidth-24)/sizes[Number(view.value)][0]*20)*5));scale();frame.scrollTo(0,0);}
function show(){const isFunction=view.value==='6';stage.innerHTML=diagrams[Number(view.value)];document.getElementById('view-note').textContent=notes[Number(view.value)];document.getElementById('diagram-heading').textContent=isFunction?'系统功能结构图':'传统 Chen ER 图';document.getElementById('function-legend').hidden=!isFunction;document.querySelectorAll('[data-er-only]').forEach(e=>e.hidden=isFunction);fit();}
view.addEventListener('change',show);zoom.addEventListener('input',scale);document.getElementById('fit').addEventListener('click',fit);
document.getElementById('export').addEventListener('click',()=>{const blob=new Blob([diagrams[Number(view.value)]],{type:'image/svg+xml;charset=utf-8'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=(view.value==='6'?'系统功能图':'概念ER图-'+view.selectedOptions[0].textContent)+'.svg';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
show();
</script></body></html>'''
    for old,new in {"__TITLE__":esc(TITLE),"__OPTIONS__":options,"__ROWS__":rows,"__COUNT__":str(len(RELATIONS)),"__DIAGRAMS__":svg_json,"__NOTES__":notes_json,"__SIZES__":sizes_json,"__INITIAL__":functions,"__NOTE__":esc(notes[-1])}.items():page=page.replace(old,new)
    assert "__TITLE__" not in page and "协迹" not in page
    OUT.joinpath("项目ER图.html").write_text(page,encoding="utf-8")
    print(f"生成完成：12 个功能模块，{len(ENTITIES)} 个实体，{len(svg_strings)} 个视图，{len(RELATIONS)} 项联系。")


if __name__ == "__main__":
    build()
