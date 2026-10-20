/* Browser demo only. Authorization is always enforced by Gateway and the services. */
const $ = (id) => document.getElementById(id);
let accessToken = null;
let roles = [];
let feedbackTimer;
let selectedRoomId = null;
let viewRevision = 0;
const workspacePages = [];

function operationDialog(title, description, fields = []) {
  return new Promise(resolve => {
    const dialog = $("operation-dialog");
    const form = $("operation-form");
    $("operation-title").textContent = title;
    $("operation-description").textContent = description;
    $("operation-fields").replaceChildren();
    for (const field of fields) {
      const label = node("label", field.label);
      const input = node("input");
      input.name = field.name;
      input.type = field.type || "text";
      input.value = field.value || "";
      input.required = true;
      input.maxLength = field.maxLength || 512;
      if (field.pattern) input.pattern = field.pattern;
      if (input.type === "datetime-local") input.step = "1800";
      label.append(input);
      $("operation-fields").append(label);
    }
    let result = null;
    const onSubmit = event => {
      event.preventDefault();
      result = formObject(form);
      dialog.close();
    };
    const onCancel = () => dialog.close();
    form.addEventListener("submit", onSubmit);
    $("operation-cancel").addEventListener("click", onCancel);
    dialog.addEventListener("close", () => {
      form.removeEventListener("submit", onSubmit);
      $("operation-cancel").removeEventListener("click", onCancel);
      resolve(result);
    }, { once: true });
    dialog.showModal();
  });
}

async function askText(title, description, value = "") {
  const values = await operationDialog(title, description,
    [{ name: "value", label: title, value }]);
  return values ? values.value.trim() : null;
}

async function askConfirm(description) {
  return (await operationDialog("确认操作", description)) !== null;
}

function initializeWorkspace() {
  const sections = [
    ["lectures", "讲座报名", "student", ["catalog-list", "slot-list"]],
    ["rooms", "研讨室预约", "student", ["study-room-list", "study-booking-form"]],
    ["teams", "我的组队", "student", ["study-team-create-form"]],
    ["bookings", "我的预约", "student", ["order-list", "study-booking-list"]],
    ["notifications", "消息通知", "student", ["notification-list"]],
    ["room-work", "研讨室签到", "staff", ["study-work-list"]],
    ["lecture-work", "讲座签到", "staff", ["work-list"]],
    ["windows", "研讨室运营", "staff", ["study-window-form", "study-occupancy-form"]],
    ["publish", "发布资源与场次", "staff", ["resource-form", "slot-form"]],
    ["resources", "资源管理", "staff", ["admin-list"]]
  ];
  for (const [key, title, role, targets] of sections) {
    const page = node("section", null, "workspace-page");
    page.id = `page-${key}`;
    page.hidden = true;
    page.append(node("h2", title));
    for (const target of targets) page.append($(target).closest(".card"));
    $("workspace").append(page);
    const link = node("a", title);
    link.href = `#/${key}`;
    $("workspace-nav").append(link);
    workspacePages.push({ key, title, role, page, link });
  }
  for (const id of ["student-panel", "study-room-student-panel", "staff-panel", "admin-panel"]) {
    $(id).replaceChildren();
  }
  $("study-room-list").classList.add("room-grid");
  const teamShortcut = action("选择或创建团队", () => goToPage("teams"));
  $("study-booking-form").prepend(teamShortcut);
}

async function renderWorkspace() {
  if (!accessToken) return;
  const revision = ++viewRevision;
  const student = roles.includes("STUDENT");
  const available = workspacePages.filter(page => page.role === (student ? "student" : "staff"));
  const key = location.hash.slice(2);
  const selected = available.find(page => page.key === key) || available[0];
  if (!selected) return;
  for (const entry of workspacePages) {
    entry.page.hidden = entry !== selected;
    entry.link.hidden = !available.includes(entry);
    if (entry === selected) entry.link.setAttribute("aria-current", "page");
    else entry.link.removeAttribute("aria-current");
  }
  if (key !== selected.key) history.replaceState(null, "", `#/${selected.key}`);
  $("page-feedback").hidden = true;
  const loaders = {
    lectures: loadCatalog, rooms: loadStudyRooms, teams: loadStudyTeams,
    bookings: () => Promise.all([loadOrders(), loadStudyBookings()]),
    notifications: loadNotifications, "room-work": loadStudyWork,
    "lecture-work": loadWork, windows: loadAdminStudyWindows,
    resources: loadAdminResources
  };
  try { await loaders[selected.key]?.(); }
  catch (error) { if (revision === viewRevision) show(error.message, true); }
}

function goToPage(key) {
  if (location.hash === `#/${key}`) run(renderWorkspace);
  else location.hash = `#/${key}`;
}

function setAuthMessage(message = "", error = false) {
  const target = $("auth-feedback");
  target.textContent = message;
  target.hidden = !message;
  target.classList.toggle("error", error);
}

function renderAuthView() {
  const registering = location.hash === "#/register";
  $("login-card").hidden = registering;
  $("register-card").hidden = !registering;
}

function setAuthView(view) {
  history.replaceState(null, "", `#/${view}`);
  renderAuthView();
}

function errorMessage(code, status) {
  const messages = {
    "invalid-credentials": "用户名或密码不正确，请使用注册时设置的密码。",
    "username-already-exists": "用户名或学号已被使用，请检查后重试。",
    "invalid-request": "填写内容不符合要求，请检查必填项、格式和长度。",
    "invalid-student-number": "学号格式不正确，请填写 4–32 位字母、数字、下划线或短横线。",
    "invalid-or-duplicate-student-number": "邀请学号格式错误或重复，也不能重复邀请队长本人。",
    "team-member-already-present": "该学号已在团队中或已有待确认邀请，请勿重复追加。",
    "team-not-forming": "团队当前不能修改，请先取消已有预约，或新建团队。",
    "team-too-large": "团队成员及待确认邀请已达到人数上限。",
    "team-not-found": "团队不存在，或你没有访问该团队的权限。",
    "team-invitation-not-found": "没有找到属于你当前学号的邀请。",
    "team-invitation-not-active": "邀请已经处理或失效，请刷新团队状态。",
    "team-invite-code-expired": "邀请码已过期，请联系队长重新组队。",
    "team-invite-code-not-found": "邀请码不存在，请检查是否完整复制。",
    "invalid-invite-code": "邀请码格式不正确，请完整复制队长提供的邀请码。",
    "student-number-not-bound": "请先绑定本人学号后再操作。",
    "study-team-size-or-consent-invalid": "已确认组员人数不符合房间要求，请让组员登录接受邀请。",
    "study-team-already-booked": "该团队已有预约，请先取消后再预约。",
    "study-room-outside-open-window": "所选区间不在房间开放窗口内，请重新选择日期和时间。",
    "study-room-participant-time-conflict": "有组员在该时段已预约其他研讨室，请更换时段或成员。",
    "study-room-interval-invalid": "使用区间须在同一天，开始早于结束，且为整点或半点。",
    "study-room-interval-unbookable": "使用时间须在未来，时长为 60–240 分钟。",
    "study-room-booking-not-open-yet": "尚未到预约开放日期，使用日前两天 00:00 开放。",
    "study-room-window-invalid": "开放时间须为同一天的未来区间，并使用整点或半点。",
    "study-room-daily-window-invalid": "日期范围最多 31 天，每日开始须早于结束，时间使用整点或半点。",
    "study-room-window-overlap": "所选日期已有重叠的开放窗口，请检查已有设置。",
    "study-room-check-in-member-not-in-booking": "该学号不在这笔预约的成员快照中，无法签到。",
    "study-room-check-in-window-closed": "当前不在允许签到的时间范围内。",
    "study-room-check-in-grace-expired": "已超过签到宽限时间，不能继续核验；请现场确认后登记爽约。",
    "study-room-check-in-state-conflict": "预约当前状态不允许签到，请刷新履约队列。",
    "study-room-no-show-window-not-open": "尚未超过签到宽限时间，不能登记爽约。",
    "study-room-no-show-state-conflict": "预约当前状态不允许登记爽约。",
    "study-room-complete-state-conflict": "仅已达签到人数要求、由当前处理人负责的预约可完成。",
    "study-room-cancellation-window-closed": "已过允许取消的时间，请联系工作人员。",
    "study-room-window-has-in-use-booking": "窗口内有正在使用的预约，暂不能关闭。"
  };
  const category = { 400: "填写有误", 401: "登录失效", 403: "权限不足", 404: "未找到", 409: "业务冲突", 429: "请求过于频繁", 503: "服务繁忙" }[status] || "操作失败";
  const fallback = { 401: "请重新登录。", 403: "当前账号无权进行此操作。", 429: "请稍后再试。" }[status] || "请刷新后重试，或联系工作人员。";
  return `${category}：${messages[code] || fallback}${code ? `（${code}）` : `（HTTP ${status}）`}`;
}

function node(tag, content, className) {
  const result = document.createElement(tag);
  if (content !== undefined && content !== null) result.textContent = String(content);
  if (className) result.className = className;
  return result;
}

function action(label, handler, className = "small secondary") {
  const result = node("button", label, className);
  result.type = "button";
  result.addEventListener("click", () => run(handler));
  return result;
}

function show(message, error = false) {
  const target = $("feedback");
  target.textContent = message;
  target.classList.toggle("error", error);
  if ($("page-feedback")) {
    $("page-feedback").hidden = !error;
    $("page-feedback").textContent = error ? message : "";
  }
  target.style.display = "block";
  clearTimeout(feedbackTimer);
  feedbackTimer = setTimeout(() => { target.style.display = "none"; }, 6500);
}

async function run(handler) {
  try { await handler(); } catch (error) { show(error.message || "请求失败", true); }
}

async function request(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  let response;
  try { response = await fetch(path, { ...options, headers, cache: "no-store" }); }
  catch { throw new Error("网络连接失败：请检查网络或服务是否启动，再重试当前操作。"); }
  const text = await response.text();
  let payload;
  try { payload = text ? JSON.parse(text) : null; } catch { payload = null; }
  if (!response.ok) {
    if (response.status === 401) logout();
    throw new Error(errorMessage(payload?.message, response.status));
  }
  if (payload && Object.hasOwn(payload, "code")) {
    if (payload.code !== 0) throw new Error(errorMessage(payload.message, 409));
    return payload.data;
  }
  return payload;
}

function jwtRoles(token) {
  try {
    const raw = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const encoded = raw.padEnd(Math.ceil(raw.length / 4) * 4, "=");
    const claims = JSON.parse(atob(encoded));
    return Array.isArray(claims.roles) ? claims.roles : [];
  } catch { return []; }
}

function formObject(form) { return Object.fromEntries(new FormData(form).entries()); }
function jsonBody(value) { return JSON.stringify(value); }
function localDateInputValue(value) {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, "0");
  const day = String(value.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}
function initializeStudyWindowForm() {
  const form = $("study-window-form");
  if (!form) return;
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const date = localDateInputValue(tomorrow);
  form.elements.startsOn.value = date;
  form.elements.endsOn.value = date;
}
function fill(targetId, items, render) {
  const target = $(targetId);
  target.replaceChildren();
  if (!items?.length) target.append(node("p", "暂无记录", "empty"));
  else items.forEach(item => target.append(render(item)));
}
function item(title, subtitle, status) {
  const box = node("article", null, "item");
  const head = node("div", null, "item-head");
  const labels = { PUBLISHED: "已发布", DRAFT: "草稿", OPEN: "开放中", CLOSED: "已关闭", FORMING: "组队中", BOOKED: "已有预约", ACCEPTED: "已确认", INVITED: "待接受", CONFIRMED: "预约成功", WAITLISTED: "候补中", CANCELLED: "已取消", CANCELLED_BY_ADMIN: "维护取消", CHECKED_IN: "已签到", COMPLETED: "已完成", NO_SHOW: "已爽约" };
  head.append(node("span", title), node("span", labels[status] || status, "badge"));
  box.append(head, node("small", subtitle));
  return box;
}
function actions(box, ...buttons) {
  const group = node("div", null, "actions");
  buttons.filter(Boolean).forEach(button => group.append(button));
  if (group.childElementCount) box.append(group);
  return box;
}
function date(value) { return value ? String(value).replace("T", " ").slice(0, 16) : "—"; }
function resourceTypeLabel(type) {
  return {
    LECTURE: "讲座",
    WORKSHOP: "培训 / 工作坊",
    ACTIVITY: "校园活动",
    STUDY_ROOM: "研讨室",
    MEETING_ROOM: "旧版固定场次资源"
  }[type] || type;
}

function logout() {
  document.body.classList.remove("signed-in");
  selectedRoomId = null;
  $("study-booking-form").reset();
  $("study-window-list").replaceChildren();
  $("study-occupied-list").replaceChildren();
  $("study-room-selection").textContent = "请选择一间研讨室，查看开放时间。";
  accessToken = null;
  roles = [];
  $("identity").textContent = "尚未登录";
  $("workspace").hidden = true;
  $("auth-section").hidden = false;
  $("student-panel").hidden = true;
  $("study-room-student-panel").hidden = true;
  $("staff-panel").hidden = true;
  $("admin-panel").hidden = true;
  setAuthView("login");
}

async function login(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const credentials = formObject(form);
  credentials.username = credentials.username.trim();
  setAuthMessage();
  const button = form.querySelector('button[type="submit"]');
  button.disabled = true;
  let result;
  try {
    result = await request("/api/v1/auth/login", {
      method: "POST", body: jsonBody(credentials)
    });
  } catch (error) {
    setAuthMessage(error.message, true);
    throw error;
  } finally {
    button.disabled = false;
  }
  if (!result?.accessToken) throw new Error("登录响应缺少令牌");
  accessToken = result.accessToken;
  document.body.classList.add("signed-in");
  form.reset();
  roles = jwtRoles(accessToken);
  $("identity").textContent = `已登录 · ${roles.join(" / ") || "用户"}`;
  $("auth-section").hidden = true;
  $("workspace").hidden = false;
  const student = roles.includes("STUDENT");
  const staff = roles.includes("SUPPORT_STAFF") || roles.includes("ADMIN");
  $("student-panel").hidden = !student;
  $("study-room-student-panel").hidden = !student;
  $("staff-panel").hidden = !staff;
  $("admin-panel").hidden = !staff;
  $("resource-form").hidden = !staff;
  $("slot-form").hidden = !staff;
  $("role-description").textContent = student
    ? "讲座等固定场次报名、研讨室自选区间预约与个人通知"
    : "活动场次与研讨室运营、签到履约和通知";
  show("登录成功。令牌只保留在本页内存中，刷新后需重新登录。");
  await renderWorkspace();
}

async function register(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const { confirmPassword, ...registration } = formObject(form);
  setAuthMessage();
  if (registration.password !== confirmPassword) {
    setAuthMessage("两次输入的密码不一致，请重新确认。", true);
    return;
  }
  registration.username = registration.username.trim();
  registration.studentNumber = registration.studentNumber.trim();
  registration.displayName = registration.displayName.trim();
  const button = form.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    await request("/api/v1/auth/register", {
      method: "POST", body: jsonBody(registration)
    });
  } catch (error) {
    setAuthMessage(error.message, true);
    throw error;
  } finally {
    button.disabled = false;
  }
  form.reset();
  setAuthView("login");
  $("login-form").elements.username.value = registration.username;
  $("login-form").elements.password.value = "";
  setAuthMessage("注册成功，请使用刚才设置的密码登录。用户名已自动填写。");
}

async function loadCatalog() {
  const resources = (await request("/api/v1/resources"))
    .filter(resource => resource.resourceType !== "STUDY_ROOM");
  fill("catalog-list", resources, resource => actions(
    item(resource.resourceName, `${resource.resourceCode} · ${resourceTypeLabel(resource.resourceType)}`, resource.status),
    action("查看固定场次", () => loadSlots(resource.resourceId))
  ));
}

async function loadSlots(resourceId) {
  const slots = await request(`/api/v1/resources/${resourceId}/slots`);
  fill("slot-list", slots, slot => {
    const box = item(slot.eventTitle || `${date(slot.startsAt)} — ${date(slot.endsAt)}`,
      `${date(slot.startsAt)} — ${date(slot.endsAt)} · 场次 ${slot.slotId} · 报名名额 ${slot.capacity} · 当前余量 ${slot.availableCapacity}（以提交结果为准）`, slot.status);
    if (slot.speakerName) box.append(node("small", `主讲人：${slot.speakerName}`));
    if (slot.activityDescription) box.append(node("p", slot.activityDescription, "activity-description"));
    return actions(box, action("报名 / 候补", async () => {
      const result = await request(`/api/v1/slots/${slot.slotId}/reservations`, {
        method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() }
      });
      show(`报名结果：${result.status}，订单号 ${result.reservationNo}`);
      await Promise.all([loadOrders(), loadSlots(resourceId)]);
    }));
  });
}

async function loadOrders() {
  const orders = await request("/api/v1/reservations/me");
  fill("order-list", orders, order => actions(
    item(order.reservationNo, `固定场次 ${order.slotId} · ${date(order.createdAt)}${order.waitlistPosition ? ` · 候补第 ${order.waitlistPosition} 位` : ""}`, order.status),
    ["CONFIRMED", "WAITLISTED"].includes(order.status)
      ? action("取消预约", async () => {
          if (!await askConfirm("确认取消这笔预约？")) return;
          await request(`/api/v1/reservations/${order.reservationId}`, { method: "DELETE" });
          show("预约已取消");
          await loadOrders();
        }, "small danger") : null
  ));
}

async function loadNotifications() {
  const result = await request("/api/v1/notifications/me?page=1&size=20");
  fill("notification-list", result.items, note => actions(
    item(note.title, `${note.content} · ${date(note.createdAt)}`, note.readAt ? "已读" : "未读"),
    !note.readAt ? action("标记已读", async () => {
      await request(`/api/v1/notifications/${note.id}/read`, { method: "PATCH" });
      await loadNotifications();
    }) : null
  ));
}

async function loadWork() {
  const result = await request("/api/v1/admin/reservations?page=1&size=20");
  fill("work-list", result.items, order => {
    const box = item(order.reservationNo,
      `学生 ${order.requesterReference} · 场次 ${order.slotId} · 处理人 ${order.handledByReference || "未处理"}`, order.status);
    const ops = [];
    if (order.status === "CONFIRMED") {
      ops.push(action("签到", () => operateOrder(order.reservationId, "check-in")));
      ops.push(action("登记爽约", () => operateOrder(order.reservationId, "no-show")));
    }
    if (order.status === "CHECKED_IN") ops.push(action("完成", () => operateOrder(order.reservationId, "complete")));
    return actions(box, ...ops);
  });
}

async function operateOrder(id, operation) {
  await request(`/api/v1/admin/reservations/${id}/${operation}`, { method: "PATCH" });
  show("操作已提交，通知将异步送达学生。");
  await loadWork();
}

async function loadAdminResources() {
  const resources = await request("/api/v1/admin/resources");
  const target = $("admin-list");
  target.replaceChildren();
  if (!resources.length) { target.append(node("p", "尚无资源", "empty")); return; }
  for (const resource of resources) {
    const box = item(resource.resourceName,
      `${resource.resourceCode} · ID ${resource.resourceId} · ${resourceTypeLabel(resource.resourceType)}`, resource.status);
    const resourceActions = [];
    if (resource.status === "DRAFT") resourceActions.push(action("发布", async () => {
      await request(`/api/v1/admin/resources/${resource.resourceId}/publish`, { method: "PATCH" });
      await loadAdminResources();
      if (resource.resourceType === "STUDY_ROOM") await loadAdminStudyWindows();
    }));
    if (resource.status !== "CLOSED" && roles.includes("ADMIN")) resourceActions.push(action("关闭资源", async () => {
      if (!await askConfirm("只有没有开放时段的资源才能关闭。继续？")) return;
      await request(`/api/v1/admin/resources/${resource.resourceId}/close`, { method: "PATCH" });
      await loadAdminResources();
    }, "small danger"));
    actions(box, ...resourceActions);
    const slots = await request(`/api/v1/admin/resources/${resource.resourceId}/slots`);
    for (const slot of slots) {
      const line = item(`${slot.eventTitle || `固定场次 ${slot.slotId}`}: ${date(slot.startsAt)} — ${date(slot.endsAt)}`,
        `报名名额 ${slot.capacity} · 当前余量 ${slot.availableCapacity}`, slot.status);
      if (slot.speakerName) line.append(node("small", `主讲人：${slot.speakerName}`));
      if (slot.activityDescription) line.append(node("p", slot.activityDescription, "activity-description"));
      if (slot.status === "OPEN" && roles.includes("ADMIN")) actions(line, action("维护取消", async () => {
        const reason = await askText("维护取消", "请输入取消原因（最多 300 字；将记录操作人和影响数量）");
        if (reason === null) return;
        const result = await request(`/api/v1/admin/slots/${slot.slotId}/cancel`, {
          method: "PATCH", body: jsonBody({ reason })
        });
        show(`已取消：确认订单 ${result.confirmedCount}，候补 ${result.waitlistedCount}`);
        await loadAdminResources();
      }, "small danger"));
      if (slot.status === "CANCELLED") actions(line, action("查看取消记录", async () => {
        const result = await request(`/api/v1/admin/slots/${slot.slotId}/cancellation`);
        show(`操作人 ${result.operatorReference}；原因：${result.reason}；确认 ${result.confirmedCount}；候补 ${result.waitlistedCount}`);
      }));
      box.append(line);
    }
    target.append(box);
  }
}

async function createResource(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formObject(form);
  data.noShowGraceMinutes = Number(data.noShowGraceMinutes);
  await request("/api/v1/admin/resources", { method: "POST", body: jsonBody(data) });
  show(data.resourceType === "STUDY_ROOM"
    ? "研讨室草稿已创建；发布后请开放使用窗口。"
    : "活动资源草稿已创建；发布后请开放固定场次。");
  form.reset();
  await loadAdminResources();
}

async function createSlot(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formObject(form);
  // Snowflake IDs exceed JavaScript's safe integer range; send the exact string.
  data.capacity = Number(data.capacity);
  await request("/api/v1/admin/slots", { method: "POST", body: jsonBody(data) });
  show("新的固定场次已开放。");
  form.reset();
  await loadAdminResources();
}

$("login-form").addEventListener("submit", e => run(() => login(e)));
$("register-form").addEventListener("submit", e => run(() => register(e)));
$("show-register").addEventListener("click", () => setAuthMessage());
$("show-login").addEventListener("click", () => setAuthMessage());
window.addEventListener("hashchange", () => accessToken ? run(renderWorkspace) : renderAuthView());
renderAuthView();
initializeStudyWindowForm();
initializeWorkspace();
$("resource-form").addEventListener("submit", e => run(() => createResource(e)));
$("slot-form").addEventListener("submit", e => run(() => createSlot(e)));
$("logout-button").addEventListener("click", logout);
$("reload-catalog").addEventListener("click", () => run(loadCatalog));
$("reload-orders").addEventListener("click", () => run(loadOrders));
$("reload-notifications").addEventListener("click", () => run(loadNotifications));
$("reload-work").addEventListener("click", () => run(loadWork));
$("reload-admin").addEventListener("click", () => run(loadAdminResources));

function currentClaims() {
  try {
    const raw = accessToken.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(raw.padEnd(Math.ceil(raw.length / 4) * 4, "=")));
  } catch { return {}; }
}

async function loadStudyTeams() {
  const teams = await request("/api/v1/study-teams/mine");
  const claims = currentClaims();
  fill("study-team-list", teams, team => {
    const memberLabels = { ACCEPTED: "已确认", INVITED: "待接受邀请", DECLINED: "已拒绝", LEFT: "已退出" };
    const members = team.members.map(m => `${m.studentNumber}：${memberLabels[m.status] || m.status}`).join(" · ");
    const box = item(`团队 ${team.teamId}`, members, team.status);
    const mine = team.members.find(m => m.studentNumber === claims.studentNo);
    const leader = team.leaderStudentNumber === claims.studentNo && mine?.status === "ACCEPTED";
    const ops = [];
    if (mine?.status === "INVITED" && team.status === "FORMING") {
      ops.push(action("确认加入", async () => {
        await request(`/api/v1/study-teams/${team.teamId}/accept`, { method: "POST" });
        await loadStudyTeams();
      }));
      ops.push(action("拒绝邀请", async () => {
        await request(`/api/v1/study-teams/${team.teamId}/decline`, { method: "POST" });
        await loadStudyTeams();
      }, "small danger"));
    }
    if (leader && team.status === "FORMING") {
      ops.push(action("用于预约", () => {
        $("study-booking-form").elements.teamId.value = team.teamId;
        goToPage("rooms");
        show(`已选择团队 ${team.teamId}`);
      }));
      ops.push(action("追加邀请学号", async () => {
        const studentNumber = await askText("追加邀请学号", "填写被邀请学生的学号；对方登录接受后才计入已确认人数。");
        if (!studentNumber) return;
        await request(`/api/v1/study-teams/${team.teamId}/invitations`, {
          method: "POST", body: jsonBody({ studentNumber: studentNumber.trim() })
        });
        await loadStudyTeams();
        show("邀请已发送，待对方登录接受后计入团队人数。");
      }));
      ops.push(action("关闭团队", async () => {
        if (!await askConfirm("关闭后不可继续预约，确认关闭？")) return;
        await request(`/api/v1/study-teams/${team.teamId}/close`, { method: "POST" });
        await loadStudyTeams();
      }, "small danger"));
    }
    if (!leader && mine?.status === "ACCEPTED" && team.status === "FORMING") {
      ops.push(action("退出团队", async () => {
        await request(`/api/v1/study-teams/${team.teamId}/members/me`, { method: "DELETE" });
        await loadStudyTeams();
      }, "small danger"));
    }
    return actions(box, ...ops);
  });
}

async function createStudyTeam(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const values = formObject(form);
  const invitedStudentNumbers = values.invitedStudentNumbers.split(",")
    .map(value => value.trim()).filter(Boolean);
  const result = await request("/api/v1/study-teams", {
    method: "POST", body: jsonBody({ invitedStudentNumbers })
  });
  $("created-study-invite-code").textContent = result.inviteCode || "—";
  form.reset();
  show(`团队 ${result.teamId} 已创建；邀请码请现在复制保存`);
  await loadStudyTeams();
}

async function joinStudyTeam(event) {
  event.preventDefault();
  const form = event.currentTarget;
  await request("/api/v1/study-teams/join", {
    method: "POST", body: jsonBody(formObject(form))
  });
  form.reset();
  show("已用当前登录身份确认加入团队");
  await loadStudyTeams();
}

async function loadStudyRooms() {
  const resources = (await request("/api/v1/resources"))
    .filter(resource => resource.resourceType === "STUDY_ROOM");
  fill("study-room-list", resources, room => {
    const box = item(room.resourceName,
      `${room.minParticipants}–${room.maxParticipants} 人 · 房间编号 ${room.resourceId}`,
      "正在查询开放时间");
    box.dataset.roomId = room.resourceId;
    const button = action("选择 / 查看窗口", () => selectStudyRoom(room, button));
    button.disabled = true;
    return actions(box, button);
  });
  // Bounded requests keep large resource catalogs from flooding the service.
  for (let offset = 0; offset < resources.length; offset += 4) {
    await Promise.all(resources.slice(offset, offset + 4).map(async room => {
      const box = [...$("study-room-list").children].find(box => box.dataset.roomId === room.resourceId);
      if (!box) return;
      const badge = box.querySelector(".badge");
      const button = box.querySelector("button");
      try {
        const info = await getRoomAvailability(room);
        badge.textContent = info.freeIntervals.length ? "可选择时段" : info.windows.length ? "仅可候补" : "暂无开放时间";
        badge.classList.toggle("unavailable", !info.freeIntervals.length);
        button.disabled = !info.windows.length;
        button.textContent = info.windows.length ? "选择 / 查看窗口" : "暂不可预约";
        box.append(node("small", info.windows.length
          ? `${info.windows.length} 个开放窗口 · ${info.freeIntervals.length} 段空闲时间；提交时还需核验团队人数与成员时间冲突。`
          : "当前已释放的日期内没有可用窗口，请联系员工设置或等待下一预约日开放。"));
      } catch (error) {
        badge.textContent = "开放时间查询失败";
        badge.classList.add("unavailable");
        button.disabled = false;
        button.textContent = "重试查询";
        box.append(node("small", error.message));
      }
    }));
  }
}

async function getRoomAvailability(room) {
  const publishedWindows = await request(`/api/v1/study-rooms/${room.resourceId}/windows`);
  const days = [...new Set(publishedWindows.map(window => window.startsAt.slice(0, 10)))];
  const availability = await Promise.all(days.map(day => request(
    `/api/v1/study-rooms/${room.resourceId}/availability?date=${day}`)));
  const now = new Date();
  const windows = availability.flatMap(day => day.windows).filter(window => {
    const start = Math.max(new Date(window.startsAt).getTime(), now.getTime());
    return new Date(window.endsAt).getTime() - start >= 60 * 60 * 1000;
  });
  const occupied = availability.flatMap(day => day.occupiedIntervals);
  const freeIntervals = [];
  const step = 30 * 60 * 1000;
  for (const window of windows) {
    // Round up local campus time to the next half hour; never suggest the past.
    const next = new Date(now);
    next.setSeconds(0, 0);
    next.setMinutes(Math.ceil((now.getMinutes() + (now.getSeconds() || now.getMilliseconds() ? 1 : 0)) / 30) * 30);
    let cursor = Math.max(new Date(window.startsAt).getTime(), next.getTime());
    const end = new Date(window.endsAt).getTime();
    for (const interval of occupied.slice().sort((a, b) => a.startsAt.localeCompare(b.startsAt))) {
      const busyStart = new Date(interval.startsAt).getTime();
      const busyEnd = new Date(interval.endsAt).getTime();
      if (busyEnd <= cursor || busyStart >= end) continue;
      if (busyStart - cursor >= step * 2) freeIntervals.push({ startsAt: cursor, endsAt: busyStart });
      cursor = Math.max(cursor, busyEnd);
    }
    if (end - cursor >= step * 2) freeIntervals.push({ startsAt: cursor, endsAt: end });
  }
  return { windows, occupied, freeIntervals };
}

async function selectStudyRoom(room, button) {
  const selection = $("study-room-selection");
  $("study-booking-form").elements.resourceId.value = room.resourceId;
  $("study-booking-form").elements.startsAt.value = "";
  $("study-booking-form").elements.endsAt.value = "";
  selectedRoomId = room.resourceId;
  selection.textContent = `已选择：${room.resourceName}（ID ${room.resourceId}），正在加载开放时间…`;
  $("study-window-list").replaceChildren();
  $("study-occupied-list").replaceChildren();
  button.disabled = true;
  button.textContent = "正在加载…";
  try {
    const { windows, occupied, freeIntervals } = await getRoomAvailability(room);
    if (!windows.length) {
      selectedRoomId = null;
      selection.textContent = `已选择：${room.resourceName}（ID ${room.resourceId}）。当前可预约日期内暂无开放窗口，请选择其他房间或联系员工设置开放时间。`;
      $("study-window-list").append(node("p", "暂无可预约开放窗口，并非已经预约成功。", "empty"));
      show(`已选择 ${room.resourceName}，但当前暂无可预约开放窗口。`);
      return;
    }
    fill("study-occupied-list", occupied, interval => item(
      "已被占用", `${date(interval.startsAt)} - ${date(interval.endsAt)}`, "不可预约"));
    fill("study-window-list", windows, window => item(
      `${date(window.startsAt)} – ${date(window.endsAt)}`,
      "该窗口内可自选 60–240 分钟；提交时再次校验", window.status));
    for (const interval of freeIntervals) {
      const start = new Date(interval.startsAt);
      const end = new Date(interval.endsAt);
      const localDateTime = value => `${localDateInputValue(value)}T${String(value.getHours()).padStart(2, "0")}:${String(value.getMinutes()).padStart(2, "0")}`;
      $("study-window-list").append(actions(item("空闲区间", `${date(localDateTime(start))} – ${date(localDateTime(end))}`, "可选"),
        action("填入一小时", () => {
          $("study-booking-form").elements.startsAt.value = localDateTime(start);
          $("study-booking-form").elements.endsAt.value = localDateTime(new Date(interval.startsAt + 60 * 60 * 1000));
          $("study-booking-form").scrollIntoView({ behavior: "smooth", block: "center" });
        })));
    }
    selection.textContent = `已选择：${room.resourceName}，共 ${windows.length} 个开放窗口。${freeIntervals.length ? "可点击空闲区间填入时间。" : "当前没有足够的空闲区间，提交开放窗口内的申请将进入候补。"} 最终结果还需核验团队人数、邀请确认及成员时间冲突。`;
    show(`已选择 ${room.resourceName}，请查看开放窗口后填写实际使用区间。`);
  } catch (error) {
    selection.textContent = `已选择 ${room.resourceName}，但开放时间读取失败：${error.message || "请检查服务连接后重试"}`;
    throw error;
  } finally {
    button.disabled = false;
    button.textContent = "选择 / 查看窗口";
    selection.scrollIntoView({ behavior: "smooth", block: "center" });
  }
}

async function createStudyBooking(event) {
  event.preventDefault();
  const data = formObject(event.currentTarget);
  if (!selectedRoomId || data.resourceId !== selectedRoomId) throw new Error("请先在上方选择研讨室并查看开放时间，再提交预约。");
  const result = await request("/api/v1/study-rooms/bookings", {
    method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() },
    body: jsonBody(data)
  });
  show(`研讨室预约 ${result.bookingNo}: ${result.status}`);
  await Promise.all([loadStudyBookings(), loadStudyTeams(), loadNotifications()]);
}

async function loadStudyBookings() {
  const bookings = await request("/api/v1/study-rooms/bookings/me");
  fill("study-booking-list", bookings, booking => actions(
    item(booking.bookingNo, `${date(booking.startsAt)} – ${date(booking.endsAt)} · `
      + `研讨室 ${booking.resourceId} · ${booking.studentNumbers.join("、")}`
      + ` · 已到场 ${booking.checkedInCount ?? 0}/${booking.requiredAttendanceCount ?? 1} 人`,
      booking.status),
    ["CONFIRMED", "WAITLISTED"].includes(booking.status)
      && currentClaims().studentNo === booking.leaderStudentNumber
      ? action("取消预约", async () => {
          if (!await askConfirm("确认取消该团队预约？候补可能立即递补。")) return;
          await request(`/api/v1/study-rooms/bookings/${booking.id}`, { method: "DELETE" });
          await Promise.all([loadStudyBookings(), loadStudyTeams()]);
        }, "small danger") : null
  ));
}

async function openStudyWindow(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formObject(form);
  if (data.endsOn < data.startsOn) {
    throw new Error("结束日期不能早于生效日期");
  }
  const button = form.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    await request("/api/v1/admin/study-rooms/windows/daily", {
      method: "POST", body: jsonBody(data)
    });
  } finally { button.disabled = false; }
  show(`已按日期范围开放研讨室窗口（${data.startsOn} 至 ${data.endsOn}）`);
  await loadAdminStudyWindows();
}

async function updateStudyOccupancy(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formObject(form);
  await request(`/api/v1/admin/study-rooms/${data.resourceId}/occupancy`, {
    method: "PATCH", body: jsonBody({
      minParticipants: Number(data.minParticipants), maxParticipants: Number(data.maxParticipants)
    })
  });
  show("研讨室人数要求已保存");
  await loadAdminResources();
}

async function loadAdminStudyWindows() {
  const resources = (await request("/api/v1/admin/resources"))
    .filter(resource => resource.resourceType === "STUDY_ROOM" && resource.status === "PUBLISHED");
  const target = $("admin-study-window-list");
  target.replaceChildren();
  for (const room of resources) {
    const box = item(room.resourceName,
      `${room.minParticipants}–${room.maxParticipants} 人 · ID ${room.resourceId}`, room.status);
    actions(box, action("填入研讨室 ID", () => {
      $("study-window-form").elements.resourceId.value = room.resourceId;
      $("study-occupancy-form").elements.resourceId.value = room.resourceId;
    }));
    const windows = await request(`/api/v1/admin/study-rooms/${room.resourceId}/windows`);
    for (const window of windows) {
      const line = item(`${date(window.startsAt)} – ${date(window.endsAt)}`,
        `窗口 ${window.id}`, window.status);
      actions(line, action("缩短窗口", async () => {
        const values = await operationDialog("缩短开放窗口", "只允许缩短；受影响的预约将维护取消并通知成员。", [
          { name: "startsAt", label: "新的开始时间", type: "datetime-local", value: window.startsAt.slice(0, 16) },
          { name: "endsAt", label: "新的结束时间", type: "datetime-local", value: window.endsAt.slice(0, 16) },
          { name: "reason", label: "维护原因" }
        ]);
        if (!values) return;
        const { startsAt, endsAt, reason } = values;
        await request(`/api/v1/admin/study-rooms/windows/${window.id}`, {
          method: "PATCH", body: jsonBody({ startsAt, endsAt, reason: reason.trim() })
        });
        await loadAdminStudyWindows();
        show("窗口已缩短，仅受影响的预约被维护取消");
      }));
      actions(line, action("维护关闭", async () => {
        const reason = await askText("关闭开放窗口", "填写维护取消原因，将通知受影响的团队。");
        if (!reason?.trim()) return;
        await request(`/api/v1/admin/study-rooms/windows/${window.id}/maintenance-close?reason=${encodeURIComponent(reason.trim())}`,
          { method: "POST" });
        await loadAdminStudyWindows();
        show("窗口已关闭，受影响预约已进入通知 Outbox");
      }, "small danger"));
      box.append(line);
    }
    target.append(box);
  }
}

async function loadStudyWork() {
  const bookings = await request("/api/v1/admin/study-rooms/bookings");
  fill("study-work-list", bookings, booking => {
    const box = item(booking.bookingNo,
      `${date(booking.startsAt)} – ${date(booking.endsAt)} · 房间 ${booking.resourceId}`
      + ` · 成员 ${booking.studentNumbers.join("、")}`,
      booking.status);
    box.append(node("p", `已到场 ${booking.checkedInCount ?? 0}/${booking.requiredAttendanceCount ?? 1} 人`
      + ` · ${booking.checkedInStudentNumbers?.join("、") || "暂无"}`));
    const ops = [];
    if (["CONFIRMED", "CHECKED_IN"].includes(booking.status)) {
      ops.push(action("核验组员签到", async () => {
        const studentNumber = await askText("核验组员签到", `请输入现场核验的组员学号。本次预约成员：${booking.studentNumbers.join("、")}。`);
        if (!studentNumber?.trim()) return;
        await request(`/api/v1/admin/study-rooms/bookings/${booking.id}/check-in`, {
          method: "PATCH", body: jsonBody({ studentNumber: studentNumber.trim() })
        });
        await loadStudyWork();
      }));
    }
    if (booking.status === "CONFIRMED") {
      ops.push(action("登记爽约", async () => {
        if (!await askConfirm("请先现场核实。超过该研讨室的宽限时间后才能登记爽约。")) return;
        await request(`/api/v1/admin/study-rooms/bookings/${booking.id}/no-show`, { method: "PATCH" });
        await loadStudyWork();
      }, "small danger"));
    }
    if (booking.status === "CHECKED_IN") {
      ops.push(action("完成使用", async () => {
        await request(`/api/v1/admin/study-rooms/bookings/${booking.id}/complete`, { method: "PATCH" });
        await loadStudyWork();
      }));
    }
    return actions(box, ...ops);
  });
}

$("study-team-create-form").addEventListener("submit", e => run(() => createStudyTeam(e)));
$("study-team-join-form").addEventListener("submit", e => run(() => joinStudyTeam(e)));
$("study-booking-form").addEventListener("submit", e => run(() => createStudyBooking(e)));
$("study-window-form").addEventListener("submit", e => run(() => openStudyWindow(e)));
$("study-occupancy-form").addEventListener("submit", e => run(() => updateStudyOccupancy(e)));
$("reload-teams").addEventListener("click", () => run(loadStudyTeams));
$("reload-study-rooms").addEventListener("click", () => run(loadStudyRooms));
$("reload-study-bookings").addEventListener("click", () => run(loadStudyBookings));
$("reload-study-work").addEventListener("click", () => run(loadStudyWork));
