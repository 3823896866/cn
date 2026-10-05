#!/usr/bin/env python3
# 小染自动注入 · 后端（纯 Python 标准库，零依赖，可直接运行）
# 运行:  python3 app.py   （默认 8000 端口；可用环境变量 PORT 覆盖）
# 提供:  REST API (/api/*) + 5 个独立管理页 (/、/cards.html ... ) + 静态资源

import json, os, base64, secrets, string, threading, mimetypes, time
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

BASE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(BASE, "data")
UPLOADS = os.path.join(BASE, "uploads")
ADMIN = os.path.join(BASE, "admin")
LOCK = threading.Lock()
HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8000"))


def now():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def now_iso(days=0):
    return (datetime.now() + timedelta(days=days)).strftime("%Y-%m-%d")


def ensure_data():
    os.makedirs(DATA, exist_ok=True)
    os.makedirs(UPLOADS, exist_ok=True)
    defaults = {
        "cards": [],
        "announcements": [
            {"id": 1, "title": "欢迎来到小染自动注入",
             "content": "后端已接入公告系统，可在此添加标题、内容与配图。", "image": "", "createdAt": now()}
        ],
        "update": {"icon": "", "version": "", "downloadUrl": "", "content": "", "updatedAt": ""},
        "files": [
            {"id": 1, "name": "注入插件_示例.lua", "zone": "功能", "size": "4.2KB",
             "createdAt": now(), "storagePath": ""},
            {"id": 2, "name": "角色立绘_小染.zip", "zone": "美化", "size": "12.4MB",
             "createdAt": now(), "storagePath": ""}
        ],
        "settings": {"softwareEnabled": True, "importPathDefault": "", "importPathPak": ""},
        "qa": [
            {"q": "怎么下载", "a": "请到「更新」页点击下载链接获取最新版本。"},
            {"q": "卡密", "a": "卡密在验证页输入，成功后可进入悬浮窗。"},
            {"q": "功能", "a": "小染支持 功能/美化 注入、卡密、公告、更新。说「转人工」可接入人工客服。"}
        ],
        "cs_sessions": []
    }
    for name, default in defaults.items():
        p = os.path.join(DATA, name + ".json")
        if not os.path.exists(p):
            with open(p, "w") as f:
                json.dump(default, f, ensure_ascii=False, indent=2)


def load(name):
    with open(os.path.join(DATA, name + ".json")) as f:
        return json.load(f)


def save(name, obj):
    p = os.path.join(DATA, name + ".json")
    tmp = p + ".tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
    os.replace(tmp, p)


def next_id(rows):
    return max([r.get("id", 0) for r in rows], default=0) + 1


def gen_code(length, fmt):
    if fmt == "数字":
        pool = string.digits
    elif fmt == "大小写字母":
        pool = string.ascii_uppercase + string.ascii_lowercase
    elif fmt == "字母数字":
        pool = string.ascii_uppercase + string.digits
    else:
        pool = string.ascii_uppercase + string.digits + "!@#$"
    return "".join(secrets.choice(pool) for _ in range(max(4, int(length))))


# ---------------- 业务逻辑（被 API 调用） ----------------

def api_cards_generate(body):
    length = int(body.get("length", 16))
    fmt = body.get("format", "字母数字")
    count = int(body.get("count", 1))
    valid_days = int(body.get("validDays", 365))
    with LOCK:
        cards = load("cards")
        for _ in range(max(1, min(count, 50))):
            code = gen_code(length, fmt)
            cards.append({
                "id": next_id(cards), "code": code, "length": length, "format": fmt,
                "createdAt": now(), "usedAt": "", "banned": False,
                "expiresAt": now_iso(valid_days)
            })
        save("cards", cards)
    return {"ok": True, "count": count}


def api_cards_delete(card_id):
    with LOCK:
        cards = [c for c in load("cards") if str(c.get("id")) != str(card_id)]
        save("cards", cards)
    return {"ok": True}


def api_cards_ban(card_id, banned):
    with LOCK:
        cards = load("cards")
        for c in cards:
            if str(c.get("id")) == str(card_id):
                c["banned"] = banned
        save("cards", cards)
    return {"ok": True}


def api_cards_verify(code):
    cards = load("cards")
    for c in cards:
        if c.get("code") == code:
            if c.get("banned"):
                return {"ok": False, "reason": "卡密已被封禁"}
            if c.get("usedAt"):
                return {"ok": False, "reason": "卡密已使用"}
            return {"ok": True, "card": c}
    return {"ok": False, "reason": "卡密不存在"}


def api_announce_create(body):
    with LOCK:
        rows = load("announcements")
        img = ""
        raw = body.get("image")
        if raw and raw.startswith("data:"):
            b64 = raw.split(",", 1)[1]
            img = "ann_" + str(next_id(rows)) + ".png"
            with open(os.path.join(UPLOADS, img), "wb") as f:
                f.write(base64.b64decode(b64))
        rows.append({"id": next_id(rows), "title": body.get("title", ""),
                     "content": body.get("content", ""), "image": img, "createdAt": now()})
        save("announcements", rows)
    return {"ok": True}


def api_announce_delete(aid):
    with LOCK:
        rows = [a for a in load("announcements") if str(a.get("id")) != str(aid)]
        save("announcements", rows)
    return {"ok": True}


def api_update_put(body):
    with LOCK:
        body["updatedAt"] = now()
        save("update", body)
    return {"ok": True}


def api_files_create(body):
    with LOCK:
        rows = load("files")
        path = ""
        raw = body.get("data")
        if raw:
            safe = "".join(ch for ch in os.path.basename(body.get("name", "file.bin")) if ch.isalnum() or ch in "-_.")
            path = safe
            with open(os.path.join(UPLOADS, path), "wb") as f:
                f.write(base64.b64decode(raw))
        rows.append({"id": next_id(rows), "name": body.get("name", "未命名"),
                     "zone": body.get("zone", "功能"), "size": body.get("size", ""),
                     "createdAt": now(), "storagePath": path})
        save("files", rows)
    return {"ok": True}


def api_files_delete(fid):
    with LOCK:
        rows = [f for f in load("files") if str(f.get("id")) != str(fid)]
        save("files", rows)
    return {"ok": True}


def api_settings_put(body):
    with LOCK:
        cur = load("settings")
        for k in ("softwareEnabled", "importPathDefault", "importPathPak"):
            if k in body:
                cur[k] = body[k]
        save("settings", cur)
    return {"ok": True, "settings": load("settings")}


# ---------------- 客服系统 ----------------
HUMAN_KEYWORDS = ("转人工", "人工服务", "人工客服", "转客服", "找人工", "人工", "客服")


def _cs_lookup_card(code):
    if not code:
        return {}
    for c in load("cards"):
        if c.get("code") == code:
            return c
    return {}


def api_cs_message(body):
    text = (body.get("text") or "").strip()
    image = body.get("image") or ""
    if image and image.startswith("data:"):
        b64 = image.split(",", 1)[1]
        fname = "cs_msg_%d.png" % int(time.time())
        with open(os.path.join(UPLOADS, fname), "wb") as f:
            f.write(base64.b64decode(b64))
        image = fname
    with LOCK:
        sess = load("cs_sessions")
        sid = body.get("sessionId") or ("s%d" % int(time.time() * 1000))
        s = next((x for x in sess if x["id"] == sid), None)
        if s is None:
            s = {"id": sid, "cardKey": body.get("cardKey", ""),
                 "device": body.get("device", ""), "status": "bot", "createdAt": now(), "messages": []}
            sess.append(s)
        s["messages"].append({"role": "user", "text": text, "image": image, "at": now()})
        if body.get("cardKey"):
            s["cardKey"] = body["cardKey"]
        if body.get("device"):
            s["device"] = body["device"]
        human = any(k in text for k in HUMAN_KEYWORDS)
        if human:
            s["status"] = "human"
            save("cs_sessions", sess)
            return {"ok": True, "sessionId": sid, "status": "human",
                    "reply": "已为您转接人工客服，请稍候…（可继续发送消息/图片）"}
        qa = load("qa")
        ans = ""
        for item in qa:
            if item.get("q") and item["q"] in text:
                ans = item.get("a", "")
                break
        if not ans:
            ans = "（机器人暂未匹配到答案，已为您记录；输入“人工”可转接人工客服）"
        s["messages"].append({"role": "bot", "text": ans, "image": "", "at": now()})
        s["status"] = "bot"
        save("cs_sessions", sess)
        return {"ok": True, "sessionId": sid, "status": "bot", "reply": ans}


def api_cs_reply(body):
    with LOCK:
        sess = load("cs_sessions")
        image = body.get("image") or ""
        if image and image.startswith("data:"):
            fname = "cs_agent_%d.png" % int(time.time())
            with open(os.path.join(UPLOADS, fname), "wb") as f:
                f.write(base64.b64decode(image.split(",", 1)[1]))
            image = fname
        for s in sess:
            if s["id"] == body.get("sessionId"):
                s["messages"].append({"role": "agent", "text": body.get("text", ""), "image": image, "at": now()})
                s["status"] = "agent"
        save("cs_sessions", sess)
    return {"ok": True}


def api_qa_save(body):
    with LOCK:
        save("qa", body.get("list", []))
    return {"ok": True}


# ---------------- HTTP Handler ----------------

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # 静默默认日志
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        elif isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(body)

    def _json_body(self):
        ln = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(ln) if ln else b""
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def do_OPTIONS(self):
        self._send(204, b"")

    def do_GET(self):
        parsed = urlparse(self.path)
        p = parsed.path
        if p == "/":
            return self._serve_html("index.html")
        if p == "/api/cards":
            return self._send(200, load("cards"))
        if p == "/api/announcements":
            return self._send(200, load("announcements"))
        if p == "/api/update":
            return self._send(200, load("update"))
        if p == "/api/files":
            qs = parse_qs(parsed.query)
            zone = qs.get("zone", [""])[0]
            rows = load("files")
            if zone:
                rows = [r for r in rows if r.get("zone") == zone]
            return self._send(200, rows)
        if p == "/api/settings":
            return self._send(200, load("settings"))
        if p == "/api/cs/sessions":
            with LOCK:
                rows = load("cs_sessions")
            for s in rows:
                s["card"] = _cs_lookup_card(s.get("cardKey", ""))
            return self._send(200, rows)
        if p == "/api/cs/qa":
            return self._send(200, load("qa"))
        if p.startswith("/uploads/"):
            return self._serve_file(os.path.join(UPLOADS, p[len("/uploads/"):]), is_upload=True)
        # 管理页 + 静态
        fname = p.lstrip("/")
        if not fname:
            return self._send(404, {"error": "not found"})
        local = os.path.join(ADMIN, fname)
        if os.path.isfile(local):
            ctype = "text/html; charset=utf-8" if fname.endswith(".html") else \
                    mimetypes.guess_type(fname)[0] or "application/octet-stream"
            with open(local, "rb") as f:
                return self._send(200, f.read(), ctype)
        return self._send(404, {"error": "not found"})

    def _serve_html(self, fname):
        local = os.path.join(ADMIN, fname)
        if not os.path.isfile(local):
            return self._send(404, {"error": "not found"})
        with open(local, "rb") as f:
            self._send(200, f.read(), "text/html; charset=utf-8")

    def _serve_file(self, local, is_upload=False):
        if not (is_upload or os.path.isfile(local)) or not os.path.isfile(local):
            return self._send(404, {"error": "file not found"})
        with open(local, "rb") as f:
            data = f.read()
        ctype = mimetypes.guess_type(local)[0] or "application/octet-stream"
        self._send(200, data, ctype)

    def do_POST(self):
        parsed = urlparse(self.path)
        p = parsed.path
        body = self._json_body()
        if p == "/api/cards/generate":
            return self._send(200, api_cards_generate(body))
        if p == "/api/cards/verify":
            return self._send(200, api_cards_verify(body.get("code", "")))
        if p == "/api/announcements":
            return self._send(200, api_announce_create(body))
        if p == "/api/files":
            return self._send(200, api_files_create(body))
        if p.endswith("/ban"):
            cid = p.split("/")[-2]
            return self._send(200, api_cards_ban(cid, True))
        if p.endswith("/unban"):
            cid = p.split("/")[-2]
            return self._send(200, api_cards_ban(cid, False))
        if p == "/api/update":
            return self._send(200, api_update_put(body))
        if p == "/api/cs/message":
            return self._send(200, api_cs_message(body))
        if p == "/api/cs/reply":
            return self._send(200, api_cs_reply(body))
        if p == "/api/cs/qa":
            return self._send(200, api_qa_save(body))
        return self._send(404, {"error": "unknown endpoint"})

    def do_PUT(self):
        p = urlparse(self.path).path
        body = self._json_body()
        if p == "/api/update":
            return self._send(200, api_update_put(body))
        if p == "/api/settings":
            return self._send(200, api_settings_put(body))
        return self._send(404, {"error": "unknown endpoint"})

    def do_DELETE(self):
        p = urlparse(self.path).path
        mapping = {"/api/cards/": api_cards_delete,
                   "/api/announcements/": api_announce_delete,
                   "/api/files/": api_files_delete}
        for prefix, fn in mapping.items():
            if p.startswith(prefix):
                fid = p[len(prefix):]
                if fid:
                    fn(fid)
                    return self._send(200, {"ok": True})
        return self._send(404, {"error": "unknown endpoint"})


def main():
    ensure_data()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"后端已启动: http://{HOST}:{PORT}  (管理页 http://127.0.0.1:{PORT}/)")
    server.serve_forever()


if __name__ == "__main__":
    main()
