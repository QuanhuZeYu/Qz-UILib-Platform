/** 经项目 wrapper 运行 Gradle；日志由调用方指定，不启动替代服务。 */
import { spawn } from 'node:child_process';
import { mkdirSync, openSync, closeSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const inputs = process.argv.slice(2);
const index = inputs.indexOf('--log');
const logPath = index < 0 ? null : path.resolve(root, inputs.splice(index, 2)[1]);
// DSH 环境加载器仅用于 agent 启动；普通 CI 和用户入口继承调用者完整环境。
const environmentPath = process.env.DSH_AGENT_ENVIRONMENT;
const env = environmentPath ? await (await import(environmentPath)).loadEnvironment() : { ...process.env };
const java = env.JAVA_HOME ? path.join(env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
let log;
if (logPath) { mkdirSync(path.dirname(logPath), { recursive: true }); log = openSync(logPath, 'w'); }
const child = spawn(java, ['-classpath', path.join(root, 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain', ...inputs], {
    cwd: root, env, stdio: ['ignore', log ?? 'inherit', log ?? 'inherit'], windowsHide: true,
});
const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', resolve); });
if (log !== undefined) closeSync(log);
if (logPath) console.log('Gradle log: ' + logPath);
process.exitCode = typeof code === 'number' ? code : 1;
