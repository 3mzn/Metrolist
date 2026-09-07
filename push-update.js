#!/usr/bin/env node
/**
 * push-update.js — Build, upload, and push a Metrolist FOSS release.
 *
 * Usage:
 *   node push-update.js <signed-apk-path>
 *
 * Env:
 *   SUPABASE_SERVICE_KEY  — service role key (or ~/.config/supabase-key)
 *   FIREBASE_CREDENTIALS  — path to Firebase service account JSON
 *                           (or uses GOOGLE_APPLICATION_CREDENTIALS)
 *
 * What it does:
 *   1. Reads version info from the signed APK's build config
 *   2. Computes SHA-256 of the APK
 *   3. Uploads APK to Supabase Storage (releases/<version>/)
 *   4. Updates latest-foss.json on Supabase
 *   5. Sends FCM push to metrolist_foss_updates topic
 */

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { execSync } = require("child_process");

// ── Config ────────────────────────────────────────────────────────────────

const SUPABASE_URL = "https://teeafutbybbywitdahpr.supabase.co";
const SUPABASE_BUCKET = "releases";
const FCM_TOPIC = "metrolist_foss_updates";

// ── Helpers ───────────────────────────────────────────────────────────────

function getSupabaseKey() {
  if (process.env.SUPABASE_SERVICE_KEY) return process.env.SUPABASE_SERVICE_KEY;
  const keyPath = path.join(
    process.env.HOME || process.env.USERPROFILE,
    ".config",
    "supabase-key"
  );
  if (fs.existsSync(keyPath)) return fs.readFileSync(keyPath, "utf8").trim();
  throw new Error(
    "Missing SUPABASE_SERVICE_KEY env var or ~/.config/supabase-key"
  );
}

function sha256File(filePath) {
  const hash = crypto.createHash("sha256");
  hash.update(fs.readFileSync(filePath));
  return hash.digest("hex");
}

function getFirebaseCredentials() {
  // Try explicit env var first
  if (process.env.FIREBASE_CREDENTIALS && fs.existsSync(process.env.FIREBASE_CREDENTIALS)) {
    return JSON.parse(fs.readFileSync(process.env.FIREBASE_CREDENTIALS, "utf8"));
  }
  // Try GOOGLE_APPLICATION_CREDENTIALS
  if (process.env.GOOGLE_APPLICATION_CREDENTIALS && fs.existsSync(process.env.GOOGLE_APPLICATION_CREDENTIALS)) {
    return JSON.parse(fs.readFileSync(process.env.GOOGLE_APPLICATION_CREDENTIALS, "utf8"));
  }
  // Try common locations
  const candidates = [
    path.join(process.env.HOME || process.env.USERPROFILE, ".config", "firebase-service-account.json"),
    path.join(process.env.HOME || process.env.USERPROFILE, "firebase-service-account.json"),
    path.join(__dirname, "firebase-service-account.json"),
  ];
  for (const p of candidates) {
    if (fs.existsSync(p)) return JSON.parse(fs.readFileSync(p, "utf8"));
  }
  throw new Error(
    "Missing Firebase credentials. Set FIREBASE_CREDENTIALS env var or place firebase-service-account.json in ~/.config/"
  );
}

async function supabaseUpload(key, storagePath, filePath, contentType) {
  const url = `${SUPABASE_URL}/storage/v1/object/${SUPABASE_BUCKET}/${storagePath}`;
  const fileData = fs.readFileSync(filePath);
  const resp = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${key}`,
      "Content-Type": contentType,
      "x-upsert": "true",
    },
    body: fileData,
  });
  if (!resp.ok) {
    const body = await resp.text();
    throw new Error(`Supabase upload failed (${resp.status}): ${body}`);
  }
  console.log(`  ✓ Uploaded ${storagePath}`);
}

async function supabaseUpdateJson(key, storagePath, data) {
  const url = `${SUPABASE_URL}/storage/v1/object/${SUPABASE_BUCKET}/${storagePath}`;
  const resp = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${key}`,
      "Content-Type": "application/json",
      "x-upsert": "true",
    },
    body: JSON.stringify(data, null, 2),
  });
  if (!resp.ok) {
    const body = await resp.text();
    throw new Error(`Supabase JSON update failed (${resp.status}): ${body}`);
  }
  console.log(`  ✓ Updated ${storagePath}`);
}

async function getAccessToken(creds) {
  const now = Math.floor(Date.now() / 1000);
  const jwtHeader = Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })).toString("base64url");
  const jwtClaim = Buffer.from(JSON.stringify({
    iss: creds.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })).toString("base64url");

  // Sign with private key
  const sign = crypto.createSign("RSA-SHA256");
  sign.update(`${jwtHeader}.${jwtClaim}`);
  const signature = sign.sign(creds.private_key, "base64url");

  const tokenResp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwtHeader}.${jwtClaim}.${signature}`,
  });
  if (!tokenResp.ok) {
    const body = await tokenResp.text();
    throw new Error(`Firebase token failed: ${body}`);
  }
  const { access_token } = await tokenResp.json();
  return access_token;
}

async function sendFcmPush(accessToken, versionName, versionCode) {
  const url = "https://fcm.googleapis.com/v1/projects/metrolist-app/messages:send";
  const message = {
    message: {
      topic: FCM_TOPIC,
      data: {
        type: "app_update",
        latestVersionCode: String(versionCode),
        latestVersionName: versionName,
      },
      android: {
        priority: "high",
      },
    },
  };
  const resp = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(message),
  });
  if (!resp.ok) {
    const body = await resp.text();
    throw new Error(`FCM push failed (${resp.status}): ${body}`);
  }
  console.log(`  ✓ FCM push sent to topic "${FCM_TOPIC}"`);
}

// ── Main ──────────────────────────────────────────────────────────────────

async function main() {
  const apkPath = process.argv[2];
  if (!apkPath) {
    console.error("Usage: node push-update.js <signed-apk-path>");
    process.exit(1);
  }
  if (!fs.existsSync(apkPath)) {
    console.error(`APK not found: ${apkPath}`);
    process.exit(1);
  }

  console.log("─".repeat(50));
  console.log("Metrolist FOSS Release Push");
  console.log("─".repeat(50));

  // Extract version from the APK filename or build config
  // Convention: app-foss-release-signed.apk
  // We read version from the build output
  const buildGradle = fs.readFileSync(
    path.join(__dirname, "app", "build.gradle.kts"),
    "utf8"
  );
  const versionCodeMatch = buildGradle.match(/versionCode\s*=\s*(\d+)/);
  const versionNameMatch = buildGradle.match(/versionName\s*=\s*"([^"]+)"/);
  if (!versionCodeMatch || !versionNameMatch) {
    console.error("Could not extract version from build.gradle.kts");
    process.exit(1);
  }
  const versionCode = parseInt(versionCodeMatch[1], 10);
  const versionName = versionNameMatch[1];

  console.log(`  Version: ${versionName} (${versionCode})`);
  console.log(`  APK: ${apkPath}`);

  // Compute SHA-256
  const sha256 = sha256File(apkPath);
  console.log(`  SHA-256: ${sha256}`);

  // Upload
  const supabaseKey = getSupabaseKey();
  const storagePath = `${versionCode}/app-foss-release.apk`;
  const apkUrl = `${SUPABASE_URL}/storage/v1/object/public/${SUPABASE_BUCKET}/${storagePath}`;

  console.log("\nUploading APK...");
  await supabaseUpload(supabaseKey, storagePath, apkPath, "application/vnd.android.package-archive");

  // Update latest-foss.json
  const latestData = {
    latestVersionCode: versionCode,
    latestVersionName: versionName,
    apkUrl,
    apkSha256: sha256,
    releaseNotesUrl: "",
  };
  console.log("Updating latest-foss.json...");
  await supabaseUpdateJson(supabaseKey, "latest-foss.json", latestData);

  // FCM push
  console.log("Sending FCM push...");
  const firebaseCreds = getFirebaseCredentials();
  const accessToken = await getAccessToken(firebaseCreds);
  await sendFcmPush(accessToken, versionName, versionCode);

  console.log("\n" + "─".repeat(50));
  console.log("✓ Release pushed successfully!");
  console.log(`  APK public URL: ${apkUrl}`);
  console.log("─".repeat(50));
}

main().catch((err) => {
  console.error("\n✗ Push failed:", err.message);
  process.exit(1);
});
