# 1Click Apply

Apply to LinkedIn job posts in one click — upload any PDF resume, auto-extract recruiter emails & requirements, attach your CV, and dispatch formatted applications + follow-ups directly from your Gmail.

---

## How to Build & Share APK via GitHub

This repository is pre-configured with a GitHub Actions workflow (`.github/workflows/main.yml`) using **JDK 21** and **Gradle 9.3.1** that automatically runs unit tests and builds a ready-to-install APK (`1ClickApply-v1.0.apk`) signed with the project's Firebase debug keystore so **Google Sign-In works immediately**.

### Step 1: Push Project to GitHub
1. In Google AI Studio, open the **Settings / Export** menu and click **Push to GitHub** (or push this folder to a `main` / `master` branch on GitHub).
2. As soon as the code reaches GitHub, the **Build & Share 1Click Apply APK** workflow starts automatically under the **Actions** tab.

### Step 2: Get a Public Shareable APK Download Link
1. Open your GitHub repository -> **Releases** (on the right sidebar) -> **1Click Apply — Latest APK** (`latest-apk`).
2. Right-click **`1ClickApply-v1.0.apk`** under **Assets** and choose **Copy link address**.
3. The direct share link looks like:
   ```text
   https://github.com/<your-username>/<your-repo>/releases/download/latest-apk/1ClickApply-v1.0.apk
   ```
4. Share that link directly on WhatsApp, LinkedIn, or email — anyone with the link can tap it to download and install the APK on Android without needing a GitHub login.

> **Tip:** Make sure **Settings -> Actions -> General -> Workflow permissions** is set to **Read and write permissions** on your GitHub repository if Releases creation ever asks for write access (the workflow also declares `contents: write`).

### Option B: Download APK Directly from AI Studio
You can also generate and download the APK directly from AI Studio's **Settings -> Export / Generate APK** menu anytime.
