// 小染管理端 · 前端小工具（同源调用 /api/*）
const API = "";  // 同源；跨域部署时改成 "http://后端地址:端口"

async function req(method, path, body) {
  const opt = { method, headers: {} };
  if (body !== undefined) {
    opt.headers["Content-Type"] = "application/json";
    opt.body = JSON.stringify(body);
  }
  const r = await fetch(API + path, opt);
  const text = await r.text();
  let data = text;
  try { data = JSON.parse(text); } catch (e) { /* 非 JSON */ }
  if (!r.ok && typeof data === "object" && data.error) throw new Error(data.error);
  return data;
}
function msg(el, text, ok) {
  el.textContent = text;
  el.className = "msg " + (ok ? "ok" : "err");
}
function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}
// 读取文件为 base64
function fileToB64(file) {
  return new Promise((res, rej) => {
    const fr = new FileReader();
    fr.onload = () => res(fr.result.split(",")[1] || "");
    fr.onerror = rej;
    fr.readAsDataURL(file);
  });
}
