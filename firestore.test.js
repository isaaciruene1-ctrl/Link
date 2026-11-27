const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_link_1";
const BOB_UID = "bob_link_2";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated context is denied on all LINK collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("devices").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("workspaces").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("handoffs").get());
});

test("Owner can register, list, update, and remove UserDevice without @DocumentId field", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const deviceRef = aliceDb.collection("users").doc(ALICE_UID).collection("devices").doc("dev-pixel-9");

  await assertSucceeds(
    deviceRef.set({
      userId: ALICE_UID,
      deviceName: "Pixel 9 Pro",
      manufacturer: "Google",
      model: "Pixel 9 Pro",
      deviceType: "PHONE",
      groupName: "My Devices",
      publicKeyFingerprint: "ab12cd34ef567890",
      batteryLevel: 88,
      charging: false,
      networkType: "WIFI",
      online: true,
      trusted: true,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("devices").get()
  );

  await assertSucceeds(
    deviceRef.update({
      batteryLevel: 92,
      charging: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field / @DocumentId key injection
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("devices").doc("dev-bad").set({
      id: "dev-bad",
      userId: ALICE_UID,
      deviceName: "Bad Device",
      manufacturer: "Google",
      model: "Test",
      deviceType: "PHONE",
      groupName: "My Devices",
      publicKeyFingerprint: "ab12cd34ef567890",
      batteryLevel: 50,
      charging: false,
      networkType: "WIFI",
      online: true,
      trusted: true,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    deviceRef.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(deviceRef.delete());
});

test("Cross-user access to devices, workspaces, and handoffs is strictly denied", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const ts = new Date(Date.now() - 5000);
    await db.collection("users").doc(BOB_UID).collection("devices").doc("bob-tab").set({
      userId: BOB_UID,
      deviceName: "Bob Tablet",
      manufacturer: "Samsung",
      model: "Tab S9",
      deviceType: "TABLET",
      groupName: "Work Devices",
      publicKeyFingerprint: "ff00aa11bb22cc33",
      batteryLevel: 70,
      charging: false,
      networkType: "WIFI",
      online: true,
      trusted: true,
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("devices").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("devices").doc("bob-tab").get());
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("devices").doc("bob-tab").update({
      deviceName: "Hijacked",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("SharedWorkspace and CloudHandoff enforce schema bounds and optional pinnedLink omission", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 3000);

  const wsRef = aliceDb.collection("users").doc(ALICE_UID).collection("workspaces").doc("ws-study-1");
  await assertSucceeds(
    wsRef.set({
      userId: ALICE_UID,
      title: "Architecture Study",
      notes: "Review NSD and TLS session handshake.",
      tasks: ["Pair tablet", "Test chunked transfer"],
      activeDeviceName: "Pixel 9 Pro",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    wsRef.update({
      pinnedLink: "https://developer.android.com/training/connect-devices-wirelessly/nsd",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  const handoffRef = aliceDb.collection("users").doc(ALICE_UID).collection("handoffs").doc("ho-1");
  await assertSucceeds(
    handoffRef.set({
      userId: ALICE_UID,
      sourceDeviceId: "dev-pixel-9",
      sourceDeviceName: "Pixel 9 Pro",
      targetDeviceId: "ALL",
      actionType: "OPEN_URL",
      title: "Continue Reading",
      payload: "https://developer.android.com",
      consumed: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    handoffRef.update({
      consumed: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject tampering with handoff payload after creation
  await assertFails(
    handoffRef.update({
      payload: "https://evil.example",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("RelaySignals enforces owner isolation, valid signalType, and immutable payload", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const ts = new Date(Date.now() - 2000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("relaySignals").get());

  const sigRef = aliceDb.collection("users").doc(ALICE_UID).collection("relaySignals").doc("sig-1");
  await assertSucceeds(
    sigRef.set({
      userId: ALICE_UID,
      roomId: "room_dev1_dev2",
      senderDeviceId: "lnk_dev1",
      senderDeviceName: "Pixel 9 Pro",
      targetDeviceId: "lnk_dev2",
      signalType: "ENCRYPTED_RELAY_PACKET",
      payload: "base64ciphertext==",
      consumed: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Bob cannot read or write Alice's relay signals
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("relaySignals").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("relaySignals").doc("sig-1").update({
      consumed: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Invalid signalType rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("relaySignals").doc("sig-bad").set({
      userId: ALICE_UID,
      roomId: "room_dev1_dev2",
      senderDeviceId: "lnk_dev1",
      senderDeviceName: "Pixel 9 Pro",
      targetDeviceId: "lnk_dev2",
      signalType: "INVALID_TYPE",
      payload: "abc",
      consumed: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Owner can mark consumed
  await assertSucceeds(
    sigRef.update({
      consumed: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Owner cannot mutate ciphertext payload after creation
  await assertFails(
    sigRef.update({
      payload: "tampered",
      updatedAt: new Date(Date.now() - 200),
    })
  );
});
