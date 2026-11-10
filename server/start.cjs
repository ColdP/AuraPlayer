// Keep the API's runtime files alongside the server and honor the user's temp path.
const fs = require('node:fs')
const path = require('node:path')
const runtime = path.resolve(__dirname, '.runtime')
fs.mkdirSync(runtime, { recursive: true })
process.env.TEMP = runtime
process.env.TMP = runtime
process.env.TMPDIR = runtime
process.env.PORT ||= '3000'
process.env.ENABLE_GENERAL_UNBLOCK = 'false'
process.env.ENABLE_PROXY = 'false'
require('@neteasecloudmusicapienhanced/api/app.js')
