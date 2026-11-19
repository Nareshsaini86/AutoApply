package com.example.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.CandidateProfile
import com.example.data.model.CapturedLinkedInPost
import com.example.data.model.JobApplication

private const val LINKEDIN_FEED_URL = "https://www.linkedin.com/feed/"
private const val LINKEDIN_JOBS_BASE_URL = "https://www.linkedin.com/jobs/"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LinkedInScreen(
    profile: CandidateProfile?,
    capturedPost: CapturedLinkedInPost?,
    duplicateMatches: List<JobApplication>,
    isSending: Boolean,
    onPostCaptured: (rawText: String, explicitEmail: String) -> Unit,
    onDismissCapturedPost: () -> Unit,
    onApplyFromCapturedPost: (autoSend: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(LINKEDIN_FEED_URL) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val resumeRoleQuery = remember(profile?.preferredSearchQuery, profile?.currentRole, profile?.skillsSummary) {
        deriveLinkedInRoleQuery(profile)
    }
    val hiringSearchUrl = remember(resumeRoleQuery) {
        val kw = if (resumeRoleQuery.isNotBlank()) "$resumeRoleQuery hiring email" else "hiring email"
        "https://www.linkedin.com/search/results/content/?keywords=${Uri.encode(kw)}"
    }
    val jobsSearchUrl = remember(resumeRoleQuery) {
        if (resumeRoleQuery.isNotBlank()) {
            "https://www.linkedin.com/jobs/search/?keywords=${Uri.encode(resumeRoleQuery)}"
        } else {
            LINKEDIN_JOBS_BASE_URL
        }
    }

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    val bridge = remember(onPostCaptured) {
        object {
            @JavascriptInterface
            fun onPostSelected(rawText: String, detectedEmail: String) {
                mainHandler.post {
                    onPostCaptured(rawText, detectedEmail)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Compact LinkedIn Browser Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = { webViewRef?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.testTag("linkedin_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "LinkedIn back"
                        )
                    }
                    IconButton(
                        onClick = { webViewRef?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.testTag("linkedin_forward_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = "LinkedIn forward"
                        )
                    }
                    IconButton(
                        onClick = { webViewRef?.reload() },
                        modifier = Modifier.testTag("linkedin_reload_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Reload LinkedIn"
                        )
                    }

                    Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(
                            text = "LinkedIn Connect",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = if (resumeRoleQuery.isNotBlank()) {
                                "Resume match: $resumeRoleQuery • Tap post"
                            } else {
                                "Tap any hiring post to extract email & CV"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = {
                            webViewRef?.evaluateJavascript(EXTRACT_VISIBLE_POST_JS, null)
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("linkedin_extract_visible_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Extract Post", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { webViewRef?.loadUrl(LINKEDIN_FEED_URL) },
                        label = { Text("Feed") }
                    )
                    AssistChip(
                        onClick = { webViewRef?.loadUrl(hiringSearchUrl) },
                        label = {
                            Text(
                                if (resumeRoleQuery.isNotBlank()) "Hiring: $resumeRoleQuery" else "Hiring Posts"
                            )
                        },
                        modifier = Modifier.testTag("linkedin_resume_hiring_chip")
                    )
                    AssistChip(
                        onClick = { webViewRef?.loadUrl(jobsSearchUrl) },
                        label = {
                            Text(
                                if (resumeRoleQuery.isNotBlank()) "Jobs: $resumeRoleQuery" else "LinkedIn Jobs"
                            )
                        }
                    )
                }

                if (loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)

                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        addJavascriptInterface(bridge, "ApplyFlowBridge")

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri = request?.url ?: return false
                                val urlStr = uri.toString()
                                if (urlStr.startsWith("mailto:", ignoreCase = true)) {
                                    val email = urlStr.removePrefix("mailto:")
                                        .substringBefore("?").trim()
                                    if (email.contains("@")) {
                                        onPostCaptured(
                                            "Recruiter Email: $email",
                                            email
                                        )
                                    }
                                    return true
                                }
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                currentUrl = url.orEmpty()
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                                if (url?.contains("linkedin.com", ignoreCase = true) == true) {
                                    view?.evaluateJavascript(INJECT_CLICK_LISTENER_JS, null)
                                }
                                CookieManager.getInstance().flush()
                            }
                        }

                        loadUrl(LINKEDIN_FEED_URL)
                        webViewRef = this
                    }
                },
                update = { view ->
                    canGoBack = view.canGoBack()
                    canGoForward = view.canGoForward()
                },
                modifier = Modifier.fillMaxSize()
            )

            // Floating Captured Post Action Card
            androidx.compose.animation.AnimatedVisibility(
                visible = capturedPost != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            ) {
                if (capturedPost != null) {
                    val hasDuplicate = capturedPost.recruiterEmail.isNotBlank() &&
                        duplicateMatches.any {
                            it.recruiterEmail.equals(capturedPost.recruiterEmail, ignoreCase = true) ||
                                it.companyName.equals(capturedPost.companyName, ignoreCase = true)
                        }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = MaterialTheme.shapes.large,
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("linkedin_captured_post_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.Email,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Column {
                                        Text(
                                            text = if (capturedPost.recruiterEmail.isNotBlank()) {
                                                "${capturedPost.companyName} • ${capturedPost.recruiterEmail}"
                                            } else {
                                                "LinkedIn Post Captured (${capturedPost.jobTitle})"
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = capturedPost.rawText.replace("\n", " "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = onDismissCapturedPost,
                                    modifier = Modifier.testTag("dismiss_captured_post_button")
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Dismiss captured post")
                                }
                            }

                            if (hasDuplicate) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.WarningAmber,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "You already applied to ${capturedPost.companyName}!",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onApplyFromCapturedPost(false) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("linkedin_review_format_button")
                                ) {
                                    Text("Review Format")
                                }
                                Button(
                                    onClick = { onApplyFromCapturedPost(true) },
                                    enabled = !isSending && capturedPost.recruiterEmail.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary,
                                        contentColor = MaterialTheme.colorScheme.onTertiary
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("linkedin_auto_send_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Send,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Auto-Send + CV")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun deriveLinkedInRoleQuery(profile: CandidateProfile?): String {
    if (profile == null) return ""
    val explicit = profile.preferredSearchQuery.trim()
    if (explicit.isNotBlank()) return explicit

    val role = profile.currentRole
        .replace(Regex("\\b(Sr\\.?|Senior|Junior|Jr\\.?|Lead|Intern)\\b", RegexOption.IGNORE_CASE), "")
        .replace("(", "")
        .replace(")", "")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (role.isNotBlank()) {
        return role.split(" ").take(3).joinToString(" ")
    }

    val topSkill = profile.skillsSummary.split(",")
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    return topSkill
}

private const val INJECT_CLICK_LISTENER_JS = """
(function() {
  if (window.__applyFlowInjected) return;
  window.__applyFlowInjected = true;
  var emailRegex = /[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}/;
  function findPostNode(el) {
    var cur = el;
    for (var i = 0; i < 12 && cur; i++) {
      if (cur.tagName === 'ARTICLE' ||
          (cur.getAttribute && (cur.getAttribute('data-urn') || cur.getAttribute('data-id'))) ||
          (cur.className && typeof cur.className === 'string' &&
            (cur.className.indexOf('feed-shared-update') >= 0 ||
             cur.className.indexOf('occludable-update') >= 0 ||
             cur.className.indexOf('update-components-text') >= 0))) {
        return cur;
      }
      cur = cur.parentElement;
    }
    return el;
  }
  document.addEventListener('click', function(ev) {
    try {
      var target = ev.target;
      if (!target) return;
      var anchor = target.closest ? target.closest('a[href^="mailto:"]') : null;
      if (anchor) {
        var mail = anchor.getAttribute('href').replace(/^mailto:/i, '').split('?')[0];
        var postNode = findPostNode(anchor);
        var txt = (postNode && postNode.innerText) ? postNode.innerText : mail;
        window.ApplyFlowBridge.onPostSelected(txt.slice(0, 3500), mail);
        return;
      }
      var sel = window.getSelection ? String(window.getSelection()).trim() : '';
      if (sel.length > 15 && emailRegex.test(sel)) {
        var m = sel.match(emailRegex);
        window.ApplyFlowBridge.onPostSelected(sel.slice(0, 3500), m ? m[0] : '');
        return;
      }
      var node = findPostNode(target);
      var txt = (node && node.innerText) ? node.innerText.trim() : '';
      if (txt.length >= 25) {
        var match = txt.match(emailRegex);
        if (match || txt.toLowerCase().indexOf('hiring') >= 0 || txt.toLowerCase().indexOf('resume') >= 0 || txt.toLowerCase().indexOf('cv') >= 0) {
          window.ApplyFlowBridge.onPostSelected(txt.slice(0, 3500), match ? match[0] : '');
        }
      }
    } catch (e) {}
  }, true);
})();
"""

private const val EXTRACT_VISIBLE_POST_JS = """
(function() {
  var emailRegex = /[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}/;
  var sel = window.getSelection ? String(window.getSelection()).trim() : '';
  if (sel.length > 12) {
    var sm = sel.match(emailRegex);
    window.ApplyFlowBridge.onPostSelected(sel.slice(0, 3500), sm ? sm[0] : '');
    return;
  }
  var candidates = document.querySelectorAll('article, [data-urn], .feed-shared-update-v2, .update-components-text, main section, div');
  var vh = window.innerHeight || 800;
  for (var i = 0; i < candidates.length; i++) {
    var el = candidates[i];
    var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
    if (!rect || rect.bottom < 0 || rect.top > vh) continue;
    var t = (el.innerText || '').trim();
    if (t.length >= 30 && t.length <= 4000) {
      var m = t.match(emailRegex);
      if (m) {
        window.ApplyFlowBridge.onPostSelected(t, m[0]);
        return;
      }
    }
  }
  var bodyTxt = (document.body && document.body.innerText) ? document.body.innerText : '';
  var bm = bodyTxt.match(emailRegex);
  if (bm) {
    var idx = bodyTxt.indexOf(bm[0]);
    var slice = bodyTxt.slice(Math.max(0, idx - 400), Math.min(bodyTxt.length, idx + 400));
    window.ApplyFlowBridge.onPostSelected(slice, bm[0]);
  }
})();
"""
