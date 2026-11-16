const MIN_BUG_BODY_LENGTH = 60;
const MIN_FEATURE_BODY_LENGTH = 20;
const COMMENT_MARKER = '<!-- auraplayer-invalid-report -->';

function stripNonUserText(body = '') {
  const content = (body || '').replace(/<!--.*?-->/gs, '');
  const userText = content
    .replace(/^#{1,6}\s+.*$/gm, '')
    .replace(/!?\[([^\]]*)\]\(https?:\/\/[^\s)]+\)/gi, '')
    .replace(/https?:\/\/[^\s<>]+/gi, '')
    .replace(/^\s*`{3,}[^\n]*$/gm, '')
    .replace(/_No response_/gi, '')
    .replace(/[`*_>#]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return { content, userText };
}

function isFeatureRequestIssue(issue = {}) {
  const title = (issue.title || '').trim();
  const body = issue.body || '';
  if (!title.startsWith('[New Features]:')) return false;
  return /^###\s+新功能名称/m.test(body) && /^###\s+大致效果描述/m.test(body);
}

function validateFeatureReport(body = '') {
  const { userText } = stripNonUserText(body);
  const reasons = [];
  if ([...userText].length < MIN_FEATURE_BODY_LENGTH) {
    reasons.push(`新功能建议有效内容不足 ${MIN_FEATURE_BODY_LENGTH} 个字符，请补充新功能名称、效果描述与使用场景。`);
  }
  return reasons;
}

function validateReport(body = '') {
  const { content, userText } = stripNonUserText(body);
  // GitHub-hosted log files and image attachments count; external links and fenced code blocks do not.
  const attachmentPattern = /https:\/\/github\.com\/(?:user-attachments\/assets\/[0-9a-f-]+|(?:user-attachments\/files|[^/\s]+\/[^/\s]+\/files)\/\d+\/[^\s<>"`)]*\.(?:log|txt|zip|png|jpe?g|gif|webp))(?=$|[\s<>"`)\]?#])/giu;
  const hasAttachment = attachmentPattern.test(content.replace(/```[^\n]*\n[\s\S]*?```/g, ''));
  const hasLogKeyword = /(?:\blogcat\b|日志|ログ|\blog(?:s)?\b|fatal exception|androidruntime|java\.lang\.|kotlin\.|\bexception\b|stack\s*trace|\b[VDIWEF]\/\w+|\banr\b|tombstone)/iu.test(userText);
  const reasons = [];
  if ([...userText].length < MIN_BUG_BODY_LENGTH) {
    reasons.push(`正文有效内容不足 ${MIN_BUG_BODY_LENGTH} 个字符（不计算表单标题、附件链接及空白占位内容）。`);
  }
  if (!hasLogKeyword) {
    reasons.push('正文缺少日志或异常关键词（如 logcat、日志、ログ、Exception、AndroidRuntime），请填写 Aura Player 版本与相关日志说明。');
  }
  if (!hasAttachment) {
    reasons.push('缺少 GitHub 上传的 `.log`、`.txt`、`.zip` 或截图附件；外部链接和代码块中的链接不计入。');
  }
  return reasons;
}

function validateIssue(issue = {}) {
  if (isFeatureRequestIssue(issue)) {
    return validateFeatureReport(issue.body);
  }
  return validateReport(issue.body);
}

function buildComment(reasons, isFeature = false) {
  if (isFeature) {
    return [
      COMMENT_MARKER,
      '',
      '此功能请求未通过自动检查，将被标记为 `invalid` 并自动关闭：',
      '',
      ...reasons.map((reason) => `- ${reason}`),
      '',
      '请使用「请求新功能」表单重新提交，并填写真实有效的功能名称与至少 20 个字符的效果描述与使用场景；若要反馈 Bug 或崩溃，请改用「Bug 报告」表单。',
      '',
      'This feature request did not pass validation and will be labeled `invalid` and closed automatically. Please fill in a clear feature name and at least 20 characters describing the expected effect and use case, or use the Bug Report form if you are reporting a bug.',
      '',
      'この機能要望は記載内容が不足しているため、`invalid` ラベルを付けて自動的に終了します。「リクエスト」フォームから機能名と 20 文字以上の詳細を記入して再投稿してください（不具合報告の場合はバグ報告フォームをご利用ください）。',
    ].join('\n');
  }

  return [
    COMMENT_MARKER,
    '',
    '此问题报告未通过自动检查（**NO LOG, NO FIX**），将被标记为 `invalid` 并自动关闭：',
    '',
    ...reasons.map((reason) => `- ${reason}`),
    '',
    '请使用「Bug 报告」表单重新提交，填写设备型号、Android / 系统版本、Aura Player 版本与 Logcat 日志，并上传相关的 `.log`、`.txt`、`.zip` 或截图文件。',
    `正文有效内容需至少 ${MIN_BUG_BODY_LENGTH} 个字符。上传前请移除网易云 Cookie、私有 API 地址与个人文件路径等敏感信息。`,
    '',
    'This report is incomplete and will be labeled `invalid` and closed automatically (NO LOG, NO FIX). Please resubmit using the Bug Report form with your device model, Android/ROM version, Aura Player version, Logcat details, at least 60 characters of actual text, and an uploaded `.log`, `.txt`, `.zip`, or screenshot file.',
    '',
    'この報告は情報が不足しているため、`invalid` ラベルを付けて自動的に終了します（NO LOG, NO FIX）。端末名、Android / システムバージョン、Aura Player バージョン、Logcat の詳細、60 文字以上の本文、アップロードした `.log`・`.txt`・`.zip` またはスクリーンショットを添えてバグ報告フォームから再投稿してください。',
  ].join('\n');
}

async function ensureInvalidLabel(github, repo) {
  try {
    await github.rest.issues.getLabel({ ...repo, name: 'invalid' });
  } catch (error) {
    if (error.status !== 404) throw error;
    try {
      await github.rest.issues.createLabel({
        ...repo,
        name: 'invalid',
        color: 'e4e669',
        description: 'Incomplete issue report / 问题报告信息不完整 / 報告の情報不足',
      });
    } catch (creationError) {
      // Another Issue run may have created the repository-wide label concurrently.
      if (creationError.status !== 422) throw creationError;
      await github.rest.issues.getLabel({ ...repo, name: 'invalid' });
    }
  }
}

async function triageIssue({ github, context, core }) {
  const repo = context.repo;
  const target = { ...repo, issue_number: context.payload.issue.number };
  // Edited/reopened events may queue; always validate the current title, body and state.
  const { data: issue } = await github.rest.issues.get(target);
  if (issue.pull_request || issue.state !== 'open') return;
  const feature = isFeatureRequestIssue(issue);
  const reasons = feature ? validateFeatureReport(issue.body) : validateReport(issue.body);
  if (reasons.length === 0) {
    core.info(`Issue #${issue.number} passed validation.`);
    return;
  }

  await ensureInvalidLabel(github, repo);
  await github.rest.issues.addLabels({ ...target, labels: ['invalid'] });
  const comments = await github.paginate(github.rest.issues.listComments, {
    ...target,
    per_page: 100,
  });
  const notified = comments.some((comment) =>
    comment.user?.type === 'Bot'
    && /^github-actions(?:\[bot\])?$/.test(comment.user?.login || '')
    && comment.body?.includes(COMMENT_MARKER));
  if (!notified) {
    await github.rest.issues.createComment({ ...target, body: buildComment(reasons, feature) });
  }
  await github.rest.issues.update({ ...target, state: 'closed', state_reason: 'not_planned' });
  core.info(`Closed Issue #${issue.number}: ${reasons.join(' ')}`);
}

module.exports = {
  validateReport,
  validateFeatureReport,
  validateIssue,
  isFeatureRequestIssue,
  buildComment,
  triageIssue,
  COMMENT_MARKER,
};
