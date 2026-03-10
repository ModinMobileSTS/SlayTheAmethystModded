'use strict';

const fs = require('node:fs');
const nodemailer = require('nodemailer');

const {
  MAIL_LOGO_CID,
  MAIL_LOGO_PATH
} = require('./constants');
const {
  escapeHtml,
  escapeHtmlAttribute,
  firstNonEmpty,
  httpError,
  summarizeInlineErrorMessage
} = require('./utils');

async function maybeSendIssueCreatedEmail(notificationState, currentConfig) {
  const issue = notificationState.issue;
  const feedbackBody = buildFeedbackSummaryForMail(notificationState);
  return maybeSendNotificationEmail({
    to: notificationState.recipient.email,
    subject: '感谢反馈！',
    text: buildCreatedIssueMailText(issue, feedbackBody),
    html: buildMailLayoutHtml({
      title: '感谢反馈！',
      introLines: [
        'Slay The Amethyst 启动器已经收到您的反馈！',
        `已创建 GitHub Issue #${issue.number}。`
      ],
      buttonLabel: `打开 Issue #${issue.number}`,
      buttonUrl: issue.htmlUrl,
      detailTitle: '具体反馈内容',
      detailBody: feedbackBody,
      secondaryMetaLines: [
        `GitHub Issue：#${issue.number}`,
        issue.htmlUrl ? `链接：${issue.htmlUrl}` : ''
      ].filter(Boolean),
      outroLines: [
        '当 issue 被解决后或有新的进展，会再向您发送一份邮件。',
        '再次感谢您的反馈！'
      ],
      signature: buildMailSignature()
    })
  }, currentConfig);
}

async function maybeSendIssueClosedEmail(notificationState, currentConfig) {
  const issue = notificationState.issue;
  return maybeSendNotificationEmail({
    to: notificationState.recipient.email,
    subject: '反馈已处理',
    text: buildClosedIssueMailText(issue),
    html: buildMailLayoutHtml({
      title: '反馈已处理',
      introLines: [
        `GitHub Issue #${issue.number} 已关闭。`,
        '如果您认为问题仍然存在，可以继续在 GitHub Issue 中补充说明，或重新提交新的反馈。'
      ],
      buttonLabel: `查看 Issue #${issue.number}`,
      buttonUrl: issue.htmlUrl,
      detailTitle: 'Issue 信息',
      detailBody: issue.title,
      signature: buildMailSignature()
    })
  }, currentConfig);
}

async function maybeSendIssueCommentEmail(notificationState, comment, currentConfig) {
  const issue = notificationState.issue;
  const commenter = firstNonEmpty(
    comment && comment.user && comment.user.login,
    'Unknown'
  );
  const commentBody = firstNonEmpty(comment && comment.body, '(评论内容为空)');
  return maybeSendNotificationEmail({
    to: notificationState.recipient.email,
    subject: '有新评论',
    text: buildIssueCommentMailText(issue, commenter, commentBody, comment && comment.html_url),
    html: buildMailLayoutHtml({
      title: '有新评论',
      introLines: [
        `GitHub Issue #${issue.number} 收到了一条新评论。`,
        `评论者：${commenter}`
      ],
      buttonLabel: '查看评论',
      buttonUrl: firstNonEmpty(comment && comment.html_url, issue.htmlUrl),
      detailTitle: '评论内容',
      detailBody: commentBody,
      secondaryMetaLines: [
        `Issue：#${issue.number} ${issue.title}`
      ],
      signature: buildMailSignature()
    })
  }, currentConfig);
}

async function maybeSendNotificationEmail(message, currentConfig) {
  if (!currentConfig.mail.enabled) {
    return {
      sentAt: null,
      messageId: null,
      skippedReason: 'mail_transport_not_configured',
      error: null
    };
  }

  try {
    const transport = getMailTransport(currentConfig);
    const attachments = [];
    if (message.html) {
      attachments.push(...buildMailInlineAttachments());
    }
    if (Array.isArray(message.attachments) && message.attachments.length > 0) {
      attachments.push(...message.attachments);
    }
    const info = await transport.sendMail({
      from: currentConfig.mail.from,
      to: message.to,
      replyTo: currentConfig.mail.replyTo || undefined,
      subject: message.subject,
      text: message.text,
      html: message.html || undefined,
      attachments: attachments.length > 0 ? attachments : undefined
    });
    return {
      sentAt: new Date().toISOString(),
      messageId: firstNonEmpty(info && info.messageId),
      skippedReason: null,
      error: null
    };
  } catch (error) {
    return {
      sentAt: null,
      messageId: null,
      skippedReason: null,
      error: summarizeInlineErrorMessage(error)
    };
  }
}

function getMailTransport(currentConfig) {
  if (!currentConfig.mail.enabled) {
    throw httpError(503, 'Mail transport is not configured');
  }
  if (currentConfig.mail.transport) {
    return currentConfig.mail.transport;
  }

  const transportOptions = {
    host: currentConfig.mail.host,
    port: currentConfig.mail.port,
    secure: currentConfig.mail.secure,
    connectionTimeout: 15_000,
    greetingTimeout: 15_000,
    socketTimeout: 20_000
  };
  if (currentConfig.mail.username) {
    transportOptions.auth = {
      user: currentConfig.mail.username,
      pass: currentConfig.mail.password
    };
  }

  currentConfig.mail.transport = nodemailer.createTransport(transportOptions);
  return currentConfig.mail.transport;
}

function buildCreatedIssueMailText(issue, feedbackBody) {
  return [
    '感谢反馈！',
    '',
    'Slay The Amethyst 启动器已经收到您的反馈！',
    `已创建 Github Issue #${issue.number}`,
    issue.htmlUrl ? `链接：${issue.htmlUrl}` : '',
    '',
    '具体反馈内容如下：',
    feedbackBody,
    '',
    '当 issue 被解决后或有新的进展，会再向您发送一份邮件。',
    '再次感谢您的反馈！',
    '',
    buildMailSignature()
  ].filter(Boolean).join('\n');
}

function buildClosedIssueMailText(issue) {
  return [
    '反馈已处理',
    '',
    `Slay The Amethyst 启动器反馈对应的 GitHub Issue #${issue.number} 已关闭。`,
    issue.htmlUrl ? `链接：${issue.htmlUrl}` : '',
    '',
    `Issue 标题：${issue.title}`,
    '',
    '如果您认为问题仍然存在，可以继续在 GitHub Issue 中补充说明，或重新提交新的反馈。',
    '',
    buildMailSignature()
  ].filter(Boolean).join('\n');
}

function buildIssueCommentMailText(issue, commenter, commentBody, commentUrl) {
  return [
    '有新评论',
    '',
    `GitHub Issue #${issue.number} 收到了一条新评论。`,
    `评论者：${commenter}`,
    commentUrl ? `链接：${commentUrl}` : '',
    '',
    '评论内容如下：',
    commentBody,
    '',
    buildMailSignature()
  ].filter(Boolean).join('\n');
}

function buildFeedbackSummaryForMail(notificationState) {
  const feedback = notificationState && notificationState.feedback ? notificationState.feedback : {};
  const blocks = [];
  if (firstNonEmpty(feedback.summary)) {
    blocks.push(`一句话总结：${feedback.summary}`);
  }
  if (firstNonEmpty(feedback.detail)) {
    blocks.push(`详细描述：\n${feedback.detail}`);
  }
  if (firstNonEmpty(feedback.reproductionSteps)) {
    blocks.push(`复现步骤：\n${feedback.reproductionSteps}`);
  }

  if (blocks.length > 0) {
    return blocks.join('\n\n');
  }
  return firstNonEmpty(feedback.formattedIssueBody, notificationState && notificationState.issue && notificationState.issue.title, '(未提供)');
}

function buildMailInlineAttachments() {
  if (!fs.existsSync(MAIL_LOGO_PATH)) {
    return [];
  }
  return [
    {
      filename: 'slay-the-amethyst-logo.png',
      content: fs.readFileSync(MAIL_LOGO_PATH),
      contentType: 'image/png',
      cid: MAIL_LOGO_CID,
      disposition: 'inline'
    }
  ];
}

function buildMailLayoutHtml(options) {
  const title = escapeHtml(options.title || '');
  const introHtml = toParagraphHtml(options.introLines);
  const outroHtml = toParagraphHtml(options.outroLines);
  const detailBody = escapeHtml(options.detailBody || '').replace(/\r\n/g, '\n').replace(/\n/g, '<br>');
  const detailTitle = escapeHtml(options.detailTitle || '详情');
  const logoHtml = fs.existsSync(MAIL_LOGO_PATH)
    ? `<img src="cid:${MAIL_LOGO_CID}" alt="Slay The Amethyst" width="56" height="56" style="display:block;width:56px;height:56px;border-radius:16px;">`
    : '';
  const buttonHtml = options.buttonUrl
    ? `<p style="margin:24px 0 0;"><a href="${escapeHtmlAttribute(options.buttonUrl)}" style="display:inline-block;padding:12px 18px;border-radius:10px;background:#6e42b6;color:#ffffff;text-decoration:none;font-weight:700;">${escapeHtml(options.buttonLabel || '查看详情')}</a></p>`
    : '';
  const secondaryMetaHtml = Array.isArray(options.secondaryMetaLines) && options.secondaryMetaLines.length > 0
    ? `<div style="margin-top:16px;padding:12px 14px;border-radius:14px;background:#f3ebff;color:#55307f;font-size:13px;line-height:1.7;border:1px solid #e4d4ff;">${options.secondaryMetaLines.map((line) => escapeHtml(line)).join('<br>')}</div>`
    : '';
  const signatureHtml = escapeHtml(options.signature || '').replace(/\r\n/g, '\n').replace(/\n/g, '<br>');

  return [
    '<!DOCTYPE html>',
    '<html lang="zh-CN">',
    '<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>',
    '<body style="margin:0;padding:0;background:#f4effc;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,PingFang SC,Hiragino Sans GB,Microsoft YaHei,sans-serif;color:#24133d;">',
    '<div style="max-width:680px;margin:0 auto;padding:32px 16px;">',
    '<div style="border-radius:4px;overflow:hidden;background:#ffffff;border:1px solid #eadfff;">',
    '<div style="padding:28px;background:#e3d2fb;border-bottom:1px solid #d8c3fb;">',
    '<div style="display:flex;align-items:center;justify-content:center;gap:16px;">',
    logoHtml,
    '<div style="text-align:center;">',
    `<h1 style="margin:0;font-size:28px;line-height:1.25;color:#2d1451;">${title}</h1>`,
    '</div>',
    '</div>',
    '</div>',
    '<div style="padding:28px;">',
    introHtml.replace(/#243b53/g, '#3b235e'),
    buttonHtml,
    `<div style="margin-top:24px;padding:18px;border-radius:18px;background:#faf6ff;border:1px solid #ebddff;"><div style="font-size:14px;font-weight:700;color:#40206b;margin-bottom:12px;">${detailTitle}</div><div style="font-size:14px;line-height:1.8;color:#4a3174;white-space:normal;">${detailBody}</div></div>`,
    secondaryMetaHtml,
    outroHtml.replace(/#243b53/g, '#3b235e'),
    `<div style="margin-top:28px;padding-top:18px;border-top:1px solid #ece1ff;font-size:13px;line-height:1.8;color:#6b5a83;">${signatureHtml}</div>`,
    '</div></div></div></body></html>'
  ].join('');
}

function toParagraphHtml(lines) {
  if (!Array.isArray(lines) || lines.length === 0) {
    return '';
  }
  return lines
    .filter(Boolean)
    .map((line) => `<p style="margin:0 0 12px;font-size:15px;line-height:1.8;color:#243b53;">${escapeHtml(line)}</p>`)
    .join('');
}

function buildMailSignature() {
  return [
    'Slay The Amethyst 启动器反馈系统',
    '感谢您的支持与反馈'
  ].join('\n');
}

module.exports = {
  maybeSendIssueCreatedEmail,
  maybeSendIssueClosedEmail,
  maybeSendIssueCommentEmail,
  maybeSendNotificationEmail,
  getMailTransport,
  buildCreatedIssueMailText,
  buildClosedIssueMailText,
  buildIssueCommentMailText,
  buildFeedbackSummaryForMail,
  buildMailInlineAttachments,
  buildMailLayoutHtml,
  toParagraphHtml,
  buildMailSignature
};
