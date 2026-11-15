/**
 * Stands in for firebase-admin so plan.js and soil.js can be run on a desk: an in-memory
 * Firestore (doc get/set only, which is all they use) and no real project. Require this FIRST.
 *
 * `fetchFrom(routes)` replaces global fetch: routes is a list of [substring, answer], where answer
 * is a JSON value, or a function (url) => JSON value, or an Error to throw. Calls are recorded.
 */
const store = new Map();
const db = {
  doc: (path) => ({
    get: async () => ({ exists: store.has(path), data: () => store.get(path) }),
    set: async (v, opt) => { store.set(path, opt && opt.merge ? { ...(store.get(path) || {}), ...v } : v); },
  }),
};
const firestore = () => db;
firestore.FieldValue = { serverTimestamp: () => Date.now(), increment: (n) => n };
const admin = { firestore, initializeApp() {}, auth: () => ({ verifyIdToken: async () => { throw new Error("no token"); } }) };

const id = require.resolve("firebase-admin");
require.cache[id] = { id, filename: id, loaded: true, exports: admin };

const calls = [];
function fetchFrom(routes) {
  calls.length = 0;
  global.fetch = async (url) => {
    calls.push(String(url));
    const hit = routes.find(([part]) => String(url).includes(part));
    if (!hit) throw new Error("unexpected fetch: " + url);
    const answer = typeof hit[1] === "function" ? hit[1](String(url)) : hit[1];
    if (answer instanceof Error) throw answer;
    if (answer && answer.__status) return { ok: false, status: answer.__status, json: async () => ({}) };
    return { ok: true, status: 200, json: async () => JSON.parse(JSON.stringify(answer)) };
  };
}

module.exports = { store, calls, fetchFrom };
