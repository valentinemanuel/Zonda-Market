import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const run = promisify(execFile);
const composeDirectory = fileURLToPath(new URL("../../", import.meta.url));

export async function compose(...args: string[]) {
  const { stdout } = await run("docker", ["compose", ...args], {
    cwd: composeDirectory,
    timeout: 45_000,
  });
  return stdout.trim();
}

export function sql(statement: string) {
  return compose("exec", "-T", "postgres", "sh", "-c",
    'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "$1"',
    "psql", statement,
  );
}
