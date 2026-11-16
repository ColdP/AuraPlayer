const { test } = require('node:test');
const assert = require('node:assert/strict');
const {
  validateReport,
  validateFeatureReport,
  validateIssue,
  isFeatureRequestIssue,
  buildComment,
  triageIssue,
  COMMENT_MARKER,
} = require('./issue-triage.cjs');

const attachment = '[aura-logcat.log](https://github.com/user-attachments/files/12345/aura-logcat.log)';
const details = '打开正在播放页面并切换为 3D 东京歌词主题后点击分享歌词，导出海报时出现闪退，连续操作三次均可稳定复现。重启应用再次进入分享页仍会复现。';
const validBody = `### 设备型号\nXiaomi 17\n### Android 与系统版本\nAndroid 16 (HyperOS OS4.0.6.0)\n### Aura Player 版本\n1.0.0\n### Logcat 日志与异常信息\nLogcat 关键日志：\njava.lang.IllegalStateException: test\n### 问题描述\n${details}\n### 上传日志或相关文件\n${attachment}`;

const validFeatureTitle = '[New Features]: 支持 WebDAV 云盘曲库';
const validFeatureBody = `### 新功能名称\n支持 WebDAV 云盘曲库\n\n### 大致效果描述与使用场景\n希望在设置中新增 WebDAV 服务器配置，挂载家庭 NAS 中的 FLAC 与 MP3 曲库并在本地媒体库中统一扫描播放。`;

function mockRun({
  title = '[Bug]: 分享歌词闪退',
  body = validBody,
  state = 'open',
  comments = [],
  labelExists = true,
  labelRace = false,
  pullRequest = false,
} = {}) {
  const calls = [];
  let labelReads = 0;
  const issues = {
    get: async (args) => {
      calls.push(['get', args]);
      return { data: { number: 42, state, title, body, ...(pullRequest ? { pull_request: {} } : {}) } };
    },
    getLabel: async (args) => {
      calls.push(['getLabel', args]);
      if (!labelExists && labelReads++ === 0) throw Object.assign(new Error('not found'), { status: 404 });
      return { data: { name: 'invalid' } };
    },
    createLabel: async (args) => {
      calls.push(['createLabel', args]);
      if (labelRace) throw Object.assign(new Error('already exists'), { status: 422 });
    },
    addLabels: async (args) => { calls.push(['addLabels', args]); },
    listComments: () => {},
    createComment: async (args) => { calls.push(['createComment', args]); },
    update: async (args) => { calls.push(['update', args]); },
  };
  return {
    calls,
    github: {
      rest: { issues },
      paginate: async (_method, args) => { calls.push(['listComments', args]); return comments; },
    },
    // Deliberately stale payload: the implementation must fetch the current issue.
    context: { repo: { owner: 'ColdP', repo: 'AuraPlayer' }, payload: { issue: { number: 42, body: 'stale payload' } } },
    core: { info() {} },
  };
}

test('complete Chinese, English and Japanese bug reports pass', () => {
  assert.deepEqual(validateReport(validBody), []);
  for (const keyword of ['logcat', 'logs', 'ログ', 'Exception', 'AndroidRuntime']) {
    assert.deepEqual(validateReport(`${details}\n${keyword}\n${attachment}`), []);
  }
});

test('short body, missing keyword and missing attachment fail independently', () => {
  assert.equal(validateReport(`日志\n${attachment}`).length, 1);
  assert.equal(validateReport(`${details}\n${attachment}`).length, 1);
  assert.equal(validateReport(`${details}\n日志`).length, 1);
  assert.equal(validateReport(null).length, 3);
});

test('body threshold rejects 59 characters and accepts exactly 60', () => {
  assert.equal(validateReport(`日志${'字'.repeat(57)}\n${attachment}`).length, 1);
  assert.deepEqual(validateReport(`日志${'字'.repeat(58)}\n${attachment}`), []);
});

test('form headings, comments, links and placeholders do not satisfy body requirements', () => {
  const body = `### Logcat 日志与异常信息\n_No response_\n<!-- 日志 ${details} -->\n[日志 ${details}](https://example.com/log.txt)\n${attachment}`;
  assert.equal(validateReport(body).length, 2);
});

test('accept current and legacy GitHub file attachments with supported extensions', () => {
  for (const url of [
    'https://github.com/user-attachments/files/12345/log.txt',
    'https://github.com/user-attachments/files/12345/logcat.ZIP',
    'https://github.com/ColdP/AuraPlayer/files/12345/crash.log',
  ]) {
    assert.deepEqual(validateReport(`${details}\n日志\n[下载文件](${url})`), []);
  }
});

test('accept GitHub screenshot attachments', () => {
  for (const screenshot of [
    'https://github.com/user-attachments/assets/01234567-89ab-cdef-0123-456789abcdef',
    'https://github.com/ColdP/AuraPlayer/files/12345/screenshot.PNG',
    ...['png', 'jpg', 'jpeg', 'gif', 'webp'].map((ext) => `https://github.com/user-attachments/files/12345/screenshot.${ext}`),
  ]) {
    assert.deepEqual(validateReport(`${details}\n日志\n![截图](${screenshot})`), []);
  }
});

test('reject external URLs, hidden attachments and pasted code links', () => {
  for (const fake of [
    'https://example.com/log.txt',
    'https://github.com.evil.example/user-attachments/files/12345/log.txt',
    'https://github.com/user-attachments/files/12345/document.pdf',
    `<!-- ${attachment} -->`,
    `\`\`\`text\n${attachment}\n\`\`\``,
  ]) {
    assert.equal(validateReport(`${details}\n日志\n${fake}`).length, 1, fake);
  }
});

test('well-formed feature requests pass without log attachments while empty ones fail', () => {
  assert.equal(isFeatureRequestIssue({ title: validFeatureTitle, body: validFeatureBody }), true);
  assert.deepEqual(validateIssue({ title: validFeatureTitle, body: validFeatureBody }), []);
  assert.equal(validateFeatureReport('### 新功能名称\n测试\n### 大致效果描述与使用场景\n_No response_').length, 1);
  // Missing feature form headings are treated as normal issues and require logs/attachments.
  assert.equal(isFeatureRequestIssue({ title: '[New Features]: bug without logs', body: '闪退了' }), false);
  assert.ok(validateIssue({ title: '[New Features]: bug without logs', body: '闪退了' }).length >= 1);
});

test('notification contains real newlines, all reasons and literal extension formatting', () => {
  const comment = buildComment(['第一项', '第二项']);
  assert.ok(comment.includes('\n- 第一项\n- 第二项\n'));
  assert.ok(comment.includes('`.log`、`.txt`、`.zip` 或截图'));
  assert.ok(comment.startsWith(COMMENT_MARKER));
});

test('valid latest bug body and feature request cause no moderation writes', async () => {
  for (const run of [
    mockRun(),
    mockRun({ title: validFeatureTitle, body: validFeatureBody }),
  ]) {
    await triageIssue(run);
    assert.deepEqual(run.calls.map(([method]) => method), ['get']);
  }
});

test('invalid body is labeled, notified and closed exactly once', async () => {
  const run = mockRun({ body: '' });
  await triageIssue(run);
  assert.deepEqual(run.calls.map(([method]) => method), ['get', 'getLabel', 'addLabels', 'listComments', 'createComment', 'update']);
  assert.deepEqual(run.calls.find(([method]) => method === 'addLabels')[1].labels, ['invalid']);
  const close = run.calls.find(([method]) => method === 'update')[1];
  assert.equal(close.state, 'closed');
  assert.equal(close.state_reason, 'not_planned');
  assert.equal(close.issue_number, 42);
});

test('missing invalid label is created automatically', async () => {
  const run = mockRun({ body: '', labelExists: false });
  await triageIssue(run);
  assert.equal(run.calls.filter(([method]) => method === 'createLabel').length, 1);
  assert.equal(run.calls.at(-1)[0], 'update');
});

test('concurrent label creation does not block closing', async () => {
  const run = mockRun({ body: '', labelExists: false, labelRace: true });
  await triageIssue(run);
  assert.equal(run.calls.filter(([method]) => method === 'getLabel').length, 2);
  assert.equal(run.calls.at(-1)[0], 'update');
});

test('reopened invalid report is closed without repeating the bot comment', async () => {
  const run = mockRun({ body: '', comments: [{ user: { type: 'Bot', login: 'github-actions[bot]' }, body: COMMENT_MARKER }] });
  await triageIssue(run);
  assert.equal(run.calls.filter(([method]) => method === 'createComment').length, 0);
  assert.equal(run.calls.at(-1)[0], 'update');
});

test('a user cannot suppress the bot comment by copying its marker', async () => {
  const run = mockRun({ body: '', comments: [{ user: { type: 'User', login: 'reporter' }, body: COMMENT_MARKER }] });
  await triageIssue(run);
  assert.equal(run.calls.filter(([method]) => method === 'createComment').length, 1);
});

test('already closed issues and pull requests are untouched', async () => {
  for (const options of [{ state: 'closed', body: '' }, { pullRequest: true, body: '' }]) {
    const run = mockRun(options);
    await triageIssue(run);
    assert.deepEqual(run.calls.map(([method]) => method), ['get']);
  }
});

test('API failures fail visibly instead of reporting successful moderation', async () => {
  const run = mockRun({ body: '' });
  run.github.rest.issues.addLabels = async () => { throw new Error('permission denied'); };
  await assert.rejects(triageIssue(run), /permission denied/);
  assert.equal(run.calls.filter(([method]) => method === 'update').length, 0);
});
