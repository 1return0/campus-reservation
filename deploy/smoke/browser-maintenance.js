/* No browser-driver dependency: exercise the real Gateway page through Chrome CDP. */
const gateway = process.env.CAMPUS_RESERVE_E2E_GATEWAY;
const studentName = process.env.CAMPUS_RESERVE_E2E_STUDENT_NAME;
const studentPassword = process.env.CAMPUS_RESERVE_E2E_STUDENT_PASSWORD;
const adminName = process.env.CAMPUS_RESERVE_E2E_ADMIN_NAME;
const adminPassword = process.env.CAMPUS_RESERVE_E2E_ADMIN_PASSWORD;
const resourceCode = process.env.CAMPUS_RESERVE_E2E_RESOURCE_CODE;
const slotId = process.env.CAMPUS_RESERVE_E2E_SLOT_ID;
const reason = process.env.CAMPUS_RESERVE_E2E_REASON;
const debuggingPort = Number(process.env.CAMPUS_RESERVE_E2E_CDP_PORT || 19222);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let target;
for (let attempt = 0; attempt < 30; attempt++) {
  try {
    const response = await fetch(`http://127.0.0.1:${debuggingPort}/json/list`);
    target = (await response.json()).find((item) => item.type === "page");
    if (target) break;
  } catch { /* Chrome is still starting. */ }
  await sleep(300);
}
if (!target) throw new Error("Chrome CDP page did not become available");

const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, { once: true });
  socket.addEventListener("error", reject, { once: true });
});
let sequence = 0;
const pending = new Map();
function command(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (message.method === "Page.javascriptDialogOpening") {
    void command("Page.handleJavaScriptDialog", {
      accept: true,
      promptText: reason,
    });
  }
  if (!message.id) return;
  const waiter = pending.get(message.id);
  if (!waiter) return;
  pending.delete(message.id);
  if (message.error) waiter.reject(new Error(message.error.message));
  else waiter.resolve(message.result);
});

async function evaluate(expression) {
  const response = await command("Runtime.evaluate", {
    expression,
    awaitPromise: true,
    returnByValue: true,
  });
  if (response.exceptionDetails) {
    throw new Error(response.exceptionDetails.text || "Browser evaluation failed");
  }
  return response.result.value;
}

async function until(expression, label, timeoutMs = 15000) {
  const expires = Date.now() + timeoutMs;
  while (Date.now() < expires) {
    if (await evaluate(expression)) return;
    await sleep(250);
  }
  const feedback = await evaluate("document.querySelector('#feedback')?.textContent || ''");
  throw new Error(`${label} timed out; feedback=${feedback}`);
}

async function login(username, password) {
  await evaluate(`(() => {
    const form = document.querySelector('#login-form');
    form.querySelector('[name=username]').value = ${JSON.stringify(username)};
    form.querySelector('[name=password]').value = ${JSON.stringify(password)};
    form.requestSubmit();
  })()`);
  await until("document.querySelector('#workspace')?.hidden === false", "login");
}

try {
  await command("Page.enable");
  await command("Runtime.enable");
  await command("Page.navigate", { url: gateway });
  await until("document.readyState === 'complete' && typeof login === 'function' && Boolean(document.querySelector('#login-form'))", "Gateway page");

  await login(studentName, studentPassword);
  await until(`Array.from(document.querySelectorAll('#catalog-list article'))
    .some(item => item.textContent.includes(${JSON.stringify(resourceCode)}))`, "catalog resource");
  await evaluate(`Array.from(document.querySelectorAll('#catalog-list article'))
    .find(item => item.textContent.includes(${JSON.stringify(resourceCode)}))
    .querySelector('button').click()`);
  await until(`Array.from(document.querySelectorAll('#slot-list article'))
    .some(item => item.textContent.includes(${JSON.stringify(slotId)}))`, "open slot");
  await evaluate(`Array.from(document.querySelectorAll('#slot-list article'))
    .find(item => item.textContent.includes(${JSON.stringify(slotId)}))
    .querySelector('button').click()`);
  await until("document.querySelector('#order-list')?.textContent.includes('CONFIRMED')", "booking");
  const orders = await evaluate("request('/api/v1/reservations/me')");
  const reservation = orders.find((order) => String(order.slotId) === slotId);
  if (!reservation || reservation.status !== "CONFIRMED") {
    throw new Error("Browser booking did not create a confirmed order");
  }

  await evaluate("document.querySelector('#logout-button').click()");
  await login(adminName, adminPassword);
  await until(`Array.from(document.querySelectorAll('#admin-list article'))
    .some(item => item.textContent.includes(${JSON.stringify(slotId)}) &&
      Array.from(item.querySelectorAll('button')).some(button => button.textContent.includes('维护取消')))` ,
    "admin cancellation action");
  await evaluate(`Array.from(document.querySelectorAll('#admin-list article'))
    .flatMap(item => Array.from(item.querySelectorAll('button')))
    .find(button => button.textContent.includes('维护取消') &&
      button.closest('article').textContent.includes(${JSON.stringify(slotId)})).click()`);
  await until(`Array.from(document.querySelectorAll('#admin-list button'))
    .some(button => button.textContent.includes('查看取消记录'))`, "admin cancellation", 20000);

  await evaluate("document.querySelector('#logout-button').click()");
  await login(studentName, studentPassword);
  await until("document.querySelector('#order-list')?.textContent.includes('CANCELLED_BY_ADMIN')",
    "student-visible order cancellation");
  const notificationLabel = "Reservation cancelled due to venue unavailability";
  for (let attempt = 0; attempt < 30; attempt++) {
    if (await evaluate(`document.querySelector('#notification-list')?.textContent.includes(
        ${JSON.stringify(notificationLabel)})`)) break;
    await evaluate("document.querySelector('#reload-notifications').click()");
    await sleep(500);
  }
  if (!(await evaluate(`document.querySelector('#notification-list')?.textContent.includes(
      ${JSON.stringify(notificationLabel)})`))) {
    throw new Error("Browser did not display the maintenance notification");
  }
  process.stdout.write(JSON.stringify({ reservationId: reservation.reservationId }) + "\n");
} finally {
  try { await command("Browser.close"); } catch { /* Chrome may already be closed. */ }
  socket.close();
}
