const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validProfilePayload(uid, ts) {
  return {
    userId: uid,
    fullName: "Naresh Saini",
    email: "nareshsaini868588@gmail.com",
    phone: "+91 8685887957",
    linkedinUrl: "https://www.linkedin.com/in/naresh-saini-3656501a5/",
    currentRole: "Sr. Software Developer",
    totalExperience: "5+ Years",
    currentCtc: "12 LPA",
    expectedCtc: "16 LPA",
    noticePeriod: "30 Days",
    currentLocation: "Hodal (Palwal), Haryana, India",
    skillsSummary: "ASP.NET Core, C#, SQL Server, REST API, React.js",
    resumeSummary: "5+ years experience in .NET Core, C#, SQL Server.",
    resumeFileName: "NareshSaini_Resume.pdf",
    autoFollowUpSend: true,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validApplicationPayload(uid, ts) {
  return {
    userId: uid,
    companyName: "WebMobril",
    companyNormalized: "webmobril",
    jobTitle: ".NET Developer",
    recruiterEmail: "priya.gupta@webmobril.com",
    recruiterName: "Priya Gupta",
    location: "Remote (India)",
    workMode: "Remote",
    rawPostText: "WE ARE HIRING | .NET DEVELOPER ... priya.gupta@webmobril.com",
    emailSubject: "Application for .NET Developer - Naresh Saini",
    emailBody: "Dear Priya, Please find my attached CV...",
    status: "APPLIED",
    gmailThreadId: "thread_abc_1",
    gmailMessageId: "msg_abc_1",
    sendMethod: "GMAIL_SMTP",
    followUpCount: 0,
    lastFollowUpSubject: "",
    lastFollowUpNote: "",
    appliedAt: ts,
    createdAt: ts,
    updatedAt: ts,
  };
}

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

test("Unauthenticated user cannot read or write profile or applications", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const ts = new Date(Date.now() - 1000);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID, ts))
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("applications").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("applications")
      .doc("app_1")
      .set(validApplicationPayload(ALICE_UID, ts))
  );
});

test("Owner can create and update CandidateProfile; shadow fields and createdAt mutation fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID, created))
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      currentCtc: "14 LPA",
      expectedCtc: "18 LPA",
      noticePeriod: "15 Days",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject createdAt mutation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Owner can create, list, update status, and record follow-up on JobApplication", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);

  // Empty collection list succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("applications").get()
  );

  // Create without optional lastFollowUpAt and without `id` field
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("applications")
      .doc("app_1")
      .set(validApplicationPayload(ALICE_UID, created))
  );

  // Update status action
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("applications")
      .doc("app_1")
      .update({
        status: "INTERVIEW",
        updatedAt: new Date(Date.now() - 2000),
      })
  );

  // Follow-up action
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("applications")
      .doc("app_1")
      .update({
        status: "FOLLOW_UP_SENT",
        followUpCount: 1,
        lastFollowUpSubject: "Re: Application for .NET Developer - Naresh Saini",
        lastFollowUpNote: "Following up politely on my application.",
        lastFollowUpAt: new Date(Date.now() - 1000),
        gmailThreadId: "thread_abc_1",
        gmailMessageId: "msg_abc_2",
        updatedAt: new Date(Date.now() - 1000),
      })
  );

  // Reject invalid status
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("applications")
      .doc("app_1")
      .update({
        status: "HIRED_INVALID",
        updatedAt: new Date(Date.now() - 500),
      })
  );

  // Reject `id` field on create
  const badPayload = validApplicationPayload(ALICE_UID, created);
  badPayload.id = "app_2";
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("applications")
      .doc("app_2")
      .set(badPayload)
  );
});

test("Cross-user access to profile and applications is strictly denied", async () => {
  const ts = new Date(Date.now() - 10000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(BOB_UID).set(validProfilePayload(BOB_UID, ts));
    await db
      .collection("users")
      .doc(BOB_UID)
      .collection("applications")
      .doc("bob_app")
      .set(validApplicationPayload(BOB_UID, ts));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").doc(BOB_UID).get());
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("applications").get()
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("applications")
      .doc("bob_app")
      .update({ status: "REJECTED", updatedAt: new Date(Date.now() - 1000) })
  );
});
