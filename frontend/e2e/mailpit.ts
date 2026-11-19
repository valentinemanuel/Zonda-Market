import { expect, type APIRequestContext } from "@playwright/test";

const mailpit = process.env.ZONDA_MAILPIT_URL ?? "http://127.0.0.1:8025";

export async function linkFromMail(request: APIRequestContext, recipient: string, path: string) {
  let link: URL | undefined;
  await expect.poll(async () => {
    const response = await request.get(`${mailpit}/api/v1/messages?limit=50`);
    if (!response.ok()) return false;
    const { messages } = await response.json() as {
      messages: { ID: string; To: { Address: string }[] }[];
    };
    for (const message of messages.filter(({ To }) => To.some(({ Address }) => Address === recipient))) {
      const detail = await request.get(`${mailpit}/api/v1/message/${message.ID}`);
      if (!detail.ok()) continue;
      const { Text } = await detail.json() as { Text: string };
      const url = Text.split(/\s+/).find((word) => word.includes(`${path}#token=`));
      if (url) {
        link = new URL(url);
        return true;
      }
    }
    return false;
  }, { timeout: 15_000, message: "Mailpit recibió el enlace de identidad" }).toBe(true);
  return link!;
}
