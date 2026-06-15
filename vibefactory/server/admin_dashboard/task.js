const el = (id) => document.getElementById(id);

document.addEventListener("DOMContentLoaded", () => {
  el("adminToken").value = localStorage.getItem("vf_admin_token") || "";
  el("adminToken").addEventListener("input", () => {
    localStorage.setItem("vf_admin_token", el("adminToken").value.trim());
  });
  el("refreshTaskButton").addEventListener("click", loadTask);
  loadTask();
});

async function loadTask() {
  const taskId = taskIdFromPath();
  if (!taskId) {
    renderLoadError("task_id를 찾을 수 없습니다.");
    return;
  }

  el("taskDetailTitle").textContent = "작업 상세 로딩 중";
  el("taskConversation").innerHTML = '<div class="muted">불러오는 중입니다.</div>';

  const headers = {};
  const token = el("adminToken").value.trim();
  if (token) headers["x-admin-token"] = token;

  try {
    const response = await fetch(`/admin/dashboard/tasks/${encodeURIComponent(taskId)}`, { headers });
    if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
    renderTaskDetail(await response.json());
  } catch (error) {
    renderLoadError(String(error.message || error));
  }
}

function taskIdFromPath() {
  const parts = window.location.pathname.split("/").filter(Boolean);
  return parts[parts.length - 1] || "";
}

function renderLoadError(message) {
  el("taskDetailTitle").textContent = "작업 상세 로드 실패";
  el("taskDetailMeta").textContent = message;
  el("taskConversation").innerHTML = "";
}

function renderTaskDetail(detail) {
  const task = detail.task || {};
  document.title = `${task.app_name || "Task"} · VibeFactory Admin`;
  el("taskDetailTitle").textContent = task.app_name || "앱 이름 없음";
  el("taskDetailMeta").textContent =
    `${task.task_id || ""} · ${task.identity || ""} · ${formatDate(task.created_at)} · ${formatDuration(task.duration_seconds)}`;
  el("taskDetailSummary").innerHTML = `
    <div>${statusPill(task.status)}</div>
    <div><strong>패키지</strong><span class="mono">${escapeHtml(task.package_name || "-")}</span></div>
    <div><strong>토큰</strong><span>${formatNumber(task.total_tokens)}</span></div>
    <div><strong>APK</strong><span>${task.apk_url ? `<a href="${escapeHtml(task.apk_url)}" target="_blank" rel="noreferrer">다운로드</a>` : "-"}</span></div>
    <div class="summary-wide"><strong>초기 요청</strong><span>${escapeHtml(task.prompt_preview || task.prompt || "")}</span></div>
  `;

  renderConversation(detail.events || []);
  renderTaskUsageRecords(detail.usage_records || []);
  renderTaskSnapshots(detail.snapshots || []);
}

function renderConversation(events) {
  const root = el("taskConversation");
  root.innerHTML = "";
  if (!events.length) {
    root.innerHTML = '<div class="muted">저장된 채팅/이벤트가 없습니다.</div>';
    return;
  }
  for (const event of events) {
    const item = document.createElement("article");
    item.className = `conversation-item ${escapeHtml(event.kind || "system")}`;
    item.innerHTML = `
      <div class="conversation-meta">
        <span>${escapeHtml(labelForKind(event.kind))}</span>
        <span>${formatDate(event.created_at)}</span>
      </div>
      <div class="conversation-content">${escapeHtml(event.content || "")}</div>
      <div class="conversation-detail">${escapeHtml(event.detail || event.event_type || "")}</div>
    `;
    root.appendChild(item);
  }
}

function renderTaskUsageRecords(rows) {
  const root = el("taskUsageRecords");
  root.innerHTML = "";
  if (!rows.length) {
    root.innerHTML = '<div class="muted">토큰 사용 기록이 없습니다.</div>';
    return;
  }
  for (const row of rows) {
    root.appendChild(listRow(
      `<strong>${escapeHtml(row.source)} · ${escapeHtml(row.model)}</strong><div class="muted">${formatDate(row.created_at)}</div>`,
      `<strong>${formatNumber(row.total_tokens)}</strong><div class="muted">in ${formatNumber(row.input_tokens)} · out ${formatNumber(row.output_tokens)}</div>`
    ));
  }
}

function renderTaskSnapshots(rows) {
  const root = el("taskSnapshots");
  root.innerHTML = "";
  if (!rows.length) return;
  root.appendChild(sectionLabel("버전 스냅샷"));
  for (const row of rows) {
    root.appendChild(listRow(
      `<strong>${escapeHtml(row.revision_label)}</strong><div class="muted">${escapeHtml(row.source)}</div>`,
      `<div class="muted">${formatDate(row.created_at)}</div>`
    ));
  }
}

function sectionLabel(text) {
  const node = document.createElement("div");
  node.className = "section-label";
  node.textContent = text;
  return node;
}

function listRow(left, right) {
  const row = document.createElement("div");
  row.className = "list-row";
  row.innerHTML = `<div>${left}</div><div>${right}</div>`;
  return row;
}

function statusPill(status) {
  const cls = String(status || "").toLowerCase().replace(/\s+/g, "-");
  return `<span class="status ${escapeHtml(cls)}">${escapeHtml(status || "-")}</span>`;
}

function labelForKind(kind) {
  return {
    user: "사용자",
    assistant: "AI",
    status: "상태",
    error: "오류",
    system: "시스템",
  }[kind] || "이벤트";
}

function formatNumber(value) {
  const number = Number(value || 0);
  return Number.isFinite(number) ? number.toLocaleString("ko-KR") : "0";
}

function formatDate(value) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

function formatDuration(value) {
  const seconds = Math.max(0, Math.round(Number(value || 0)));
  if (!seconds) return "-";
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  if (minutes < 60) return `${minutes}분 ${rest}초`;
  const hours = Math.floor(minutes / 60);
  return `${hours}시간 ${minutes % 60}분`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
