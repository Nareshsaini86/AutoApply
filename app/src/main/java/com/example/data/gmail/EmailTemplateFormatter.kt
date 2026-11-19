package com.example.data.gmail

import com.example.data.model.CandidateProfile

object EmailTemplateFormatter {

    /**
     * Normalizes plain-text email bodies (especially LLM outputs that collapse line breaks)
     * into clean paragraphs and one-item-per-line bullets.
     */
    fun normalizePlainText(rawBody: String): String {
        var text = rawBody.replace("\r\n", "\n").replace("\r", "\n").trim()

        // Break greeting onto its own paragraph if glued (e.g. "Dear Aman, I am writing...")
        text = text.replace(
            Regex("^((?:Dear|Hi|Hello)\\s+[^,\\n]{1,40},)\\s+(?=\\S)"),
            "$1\n\n"
        )

        // Break inline bullets like "details as requested: - Total Experience: ..." onto separate lines
        text = text.replace(
            Regex(":\\s+([•\\-])\\s+"),
            ":\n$1 "
        )
        text = text.replace(
            Regex("\\s+([•\\-])\\s+(?=[A-Z][A-Za-z0-9 /&]{1,35}:)"),
            "\n$1 "
        )

        // Separate closing / attachment paragraph if glued after bullet values
        text = text.replace(
            Regex("\\s+(I have attached my|Please find my|My updated resume|Thank you for your time)"),
            "\n\n$1"
        )
        text = text.replace(
            Regex("\\s+(Best regards,|Warm regards,|Regards,|Sincerely,)\\s*"),
            "\n\n$1\n"
        )
        // Put phone number on next line after name if glued in sign-off
        text = text.replace(
            Regex("(Best regards,|Warm regards,|Regards,|Sincerely,)\\n([^\\n+]{2,45}?)\\s+(\\+\\d[\\d\\s-]{7,})"),
            "$1\n$2\n$3"
        )

        // Collapse >2 consecutive blank lines
        return text.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /**
     * Converts a structured application / follow-up email into a clean, modern,
     * recruiter-friendly HTML email with styled detail rows, zero auto-linked ".NET" bugs,
     * and an executive signature card.
     */
    fun toStyledHtml(
        subject: String,
        plainBody: String,
        profile: CandidateProfile? = null,
        attachmentFileName: String? = null,
        roleBadge: String? = null
    ): String {
        val normalized = normalizePlainText(plainBody)
        val lines = normalized.lines()

        val headerTitle = roleBadge?.takeIf { it.isNotBlank() }
            ?: profile?.currentRole?.takeIf { it.isNotBlank() }
            ?: "Job Application"
        val senderName = profile?.fullName?.ifBlank { "Candidate" } ?: "Candidate"
        val senderPhone = profile?.phone.orEmpty()
        val senderEmail = profile?.email.orEmpty()
        val senderLinkedIn = profile?.linkedinUrl.orEmpty()

        val blocksHtml = StringBuilder()
        val bulletBuffer = mutableListOf<Pair<String, String>>()
        val signOffLines = mutableListOf<String>()
        var inSignOff = false

        fun flushBullets() {
            if (bulletBuffer.isEmpty()) return
            blocksHtml.append(
                """
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin:14px 0 18px 0;background-color:#F8FAFC;border:1px solid #E2E8F0;border-radius:10px;border-collapse:separate;">
                  <tbody>
                """.trimIndent()
            )
            bulletBuffer.forEachIndexed { idx, (label, value) ->
                val borderStyle = if (idx < bulletBuffer.lastIndex) "border-bottom:1px solid #E2E8F0;" else ""
                if (label.isNotBlank()) {
                    blocksHtml.append(
                        """
                        <tr>
                          <td style="padding:10px 14px;width:38%;font-size:13px;font-weight:600;color:#475569;vertical-align:top;$borderStyle">${escapeAndBreakTechLinks(label)}</td>
                          <td style="padding:10px 14px;font-size:14px;font-weight:500;color:#0F172A;vertical-align:top;$borderStyle">${linkifyUrls(escapeAndBreakTechLinks(value))}</td>
                        </tr>
                        """.trimIndent()
                    )
                } else {
                    blocksHtml.append(
                        """
                        <tr>
                          <td colspan="2" style="padding:10px 14px;font-size:14px;color:#0F172A;$borderStyle">• ${linkifyUrls(escapeAndBreakTechLinks(value))}</td>
                        </tr>
                        """.trimIndent()
                    )
                }
            }
            blocksHtml.append("</tbody></table>")
            bulletBuffer.clear()
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank()) {
                flushBullets()
                continue
            }

            if (inSignOff) {
                signOffLines += line
                continue
            }

            val lower = line.lowercase()
            if (lower.startsWith("best regards") ||
                lower.startsWith("warm regards") ||
                lower == "regards," ||
                lower.startsWith("sincerely")
            ) {
                flushBullets()
                inSignOff = true
                signOffLines += line
                continue
            }

            val bulletMatch = Regex("^[•\\-*]\\s*(.+)$").find(line)
            if (bulletMatch != null) {
                val content = bulletMatch.groupValues[1].trim()
                val colonIdx = content.indexOf(':')
                if (colonIdx in 2..40 && !content.substring(0, colonIdx).contains("http", ignoreCase = true)) {
                    val label = content.substring(0, colonIdx).trim()
                    val value = content.substring(colonIdx + 1).trim()
                    bulletBuffer += label to value
                } else {
                    bulletBuffer += "" to content
                }
                continue
            }

            flushBullets()

            when {
                lower.startsWith("dear ") || lower.startsWith("hi ") || lower.startsWith("hello ") -> {
                    blocksHtml.append(
                        """<p style="margin:0 0 14px 0;font-size:15px;font-weight:600;color:#0F172A;">${escapeAndBreakTechLinks(line)}</p>"""
                    )
                }
                line.endsWith(":") && line.length <= 60 -> {
                    blocksHtml.append(
                        """<p style="margin:18px 0 6px 0;font-size:12px;font-weight:700;letter-spacing:0.6px;text-transform:uppercase;color:#1D4ED8;">${escapeAndBreakTechLinks(line.removeSuffix(":"))}</p>"""
                    )
                }
                else -> {
                    blocksHtml.append(
                        """<p style="margin:0 0 13px 0;font-size:14.5px;line-height:1.65;color:#1E293B;">${linkifyUrls(escapeAndBreakTechLinks(line))}</p>"""
                    )
                }
            }
        }
        flushBullets()

        val closingGreeting = signOffLines.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Best regards,"
        val sigName = signOffLines.getOrNull(1)?.takeIf { it.isNotBlank() } ?: senderName
        val roleLine = profile?.currentRole?.takeIf { it.isNotBlank() } ?: ""
        val expLine = profile?.totalExperience?.takeIf { it.isNotBlank() }?.let { "$it Experience" }.orEmpty()
        val subtitleMeta = listOf(roleLine, expLine).filter { it.isNotBlank() }.joinToString(" • ")

        val contactParts = buildList {
            if (senderPhone.isNotBlank()) add("""<span style="color:#334155;">$senderPhone</span>""")
            if (senderEmail.isNotBlank()) add("""<a href="mailto:${escapeHtml(senderEmail)}" style="color:#1D4ED8;text-decoration:none;">${escapeHtml(senderEmail)}</a>""")
            if (senderLinkedIn.isNotBlank()) add("""<a href="${escapeHtml(senderLinkedIn)}" style="color:#1D4ED8;text-decoration:none;font-weight:600;">LinkedIn Profile</a>""")
        }.joinToString(" &nbsp;•&nbsp; ")

        val attachmentBannerHtml = if (!attachmentFileName.isNullOrBlank()) {
            """
            <div style="margin:16px 0 18px 0;padding:10px 14px;background-color:#EFF6FF;border:1px solid #BFDBFE;border-radius:8px;font-size:13px;color:#1E3A8A;">
              <strong>Resume Attached:</strong> ${escapeHtml(attachmentFileName)}
            </div>
            """.trimIndent()
        } else ""

        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0" />
          <title>${escapeHtml(subject)}</title>
        </head>
        <body style="margin:0;padding:16px;background-color:#F1F5F9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#0F172A;">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:640px;margin:0 auto;background-color:#FFFFFF;border-radius:14px;overflow:hidden;border:1px solid #E2E8F0;box-shadow:0 2px 8px rgba(15,23,42,0.05);">
            <tr>
              <td style="background:linear-gradient(135deg,#0F2942 0%,#1E40AF 100%);background-color:#0F2942;padding:20px 24px;">
                <div style="font-size:11px;font-weight:700;letter-spacing:1px;text-transform:uppercase;color:#93C5FD;margin-bottom:4px;">Candidate Application</div>
                <div style="font-size:18px;font-weight:700;color:#FFFFFF;line-height:1.3;">${escapeAndBreakTechLinks(headerTitle)}</div>
                <div style="font-size:13px;color:#E2E8F0;margin-top:4px;">${escapeHtml(senderName)}${if (expLine.isNotBlank()) " &nbsp;|&nbsp; ${escapeHtml(expLine)}" else ""}</div>
              </td>
            </tr>
            <tr>
              <td style="padding:24px;">
                $blocksHtml
                $attachmentBannerHtml
                <div style="margin-top:20px;padding-top:16px;border-top:1px solid #E2E8F0;">
                  <p style="margin:0 0 10px 0;font-size:14px;color:#475569;">${escapeHtml(closingGreeting)}</p>
                  <table role="presentation" cellpadding="0" cellspacing="0" style="border-left:3px solid #1E40AF;padding-left:12px;">
                    <tr>
                      <td>
                        <div style="font-size:16px;font-weight:700;color:#0F172A;">${escapeHtml(sigName)}</div>
                        ${if (subtitleMeta.isNotBlank()) """<div style="font-size:13px;color:#475569;margin-top:2px;">${escapeAndBreakTechLinks(subtitleMeta)}</div>""" else ""}
                        ${if (contactParts.isNotBlank()) """<div style="font-size:13px;margin-top:6px;line-height:1.5;">$contactParts</div>""" else ""}
                      </td>
                    </tr>
                  </table>
                </div>
              </td>
            </tr>
          </table>
        </body>
        </html>
        """.trimIndent()
    }

    /**
     * Prevents Gmail from turning tech names like "ASP.NET" or "React.js" into fake URL hyperlinks
     * by inserting a zero-width non-joiner (&#8203;) right after the dot.
     */
    private fun escapeAndBreakTechLinks(raw: String): String {
        val escaped = escapeHtml(raw)
        return escaped
            .replace(Regex("(?i)(ASP)\\.(NET)"), "$1.&#8203;$2")
            .replace(Regex("(?i)\\.(NET)\\b"), ".&#8203;$1")
            .replace(Regex("(?i)(React|Node|Vue|Next|Express)\\.(js)"), "$1.&#8203;$2")
    }

    private fun linkifyUrls(escapedHtml: String): String {
        return Regex("(https?://[^\\s<]+)").replace(escapedHtml) { match ->
            val url = match.value
            """<a href="$url" style="color:#1D4ED8;text-decoration:none;">$url</a>"""
        }
    }

    private fun escapeHtml(input: String): String = input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
